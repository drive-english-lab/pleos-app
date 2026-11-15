package io.github.driveenglishlab.pleos.data

import android.content.Context
import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

sealed interface PracticeResult {
    data class Ready(val session: PracticeSession) : PracticeResult
    data class Saved(val attemptId: UUID, val feedback: PracticeFeedback?, val waiting: Boolean) : PracticeResult
    data class Failed(val code: String, val acceptedAttemptId: UUID? = null) : PracticeResult
    data object Superseded : PracticeResult
    data object Busy : PracticeResult
}

/** Owns durable input and data results. It never starts capture, playback, speech or presentation. */
class PracticeRepository internal constructor(private val api: LearningApi, private val dao: LearningDao,
    private val store: LearningStore, contracts: LearningContractJson,
    private val isCurrent: suspend (OwnerContext, UUID, PracticeRef) -> Boolean,
    private val presentationVerifier: FeedbackPresentationVerifier = FeedbackPresentationVerifier { false },
    private val mayProjectResult: suspend (OwnerContext, UUID, UUID) -> Boolean = { _, _, _ -> true },
    private val elapsedMs: () -> Long = { System.nanoTime() / 1_000_000 }) {
    constructor(context: Context, api: LearningApi, database: LearningDatabase,
        isCurrent: suspend (OwnerContext, UUID, PracticeRef) -> Boolean,
        presentationVerifier: FeedbackPresentationVerifier = FeedbackPresentationVerifier { false },
        mayProjectResult: suspend (OwnerContext, UUID, UUID) -> Boolean = { _, _, _ -> true }) :
        this(api, database.learning(), LearningStore(database), LearningContractJson(context), isCurrent, presentationVerifier, mayProjectResult)

    private val lane = Mutex()
    private val journal = SessionJournal(dao, api, contracts)
    private var session: PracticeSession? = null
    private var operation: UUID? = null
    private val clock = PracticeRequestClock()

    suspend fun snapshot(nowMs: Long): PracticeSession? = lane.withLock {
        session?.let { value -> if (operation == null) value else value.copy(requestState = clock.state(nowMs)) }
    }

    suspend fun open(scope: LearningCallScope, lesson: LessonInput, questionEn: String,
        restored: WirePersonalLessonRecord? = null): PracticeResult {
        val legacy = lesson.contentRef as? PracticeContentRef ?: return PracticeResult.Failed("SOURCE_PROTOCOL_REQUIRED")
        if (!isCurrent(scope.owner, lesson.lessonId, lesson.contentRef)) return PracticeResult.Superseded
        if (restored != null) {
            require(restored.lessonId == lesson.lessonId.toString() && restored.contentRef == lesson.contentRef.commonWire())
            dao.importLessonBinding(scope.owner, lesson.lessonId, legacy, restored.dialogueProgress.hintMethodAnnounced)
            // The caller has already obtained this authoritative record through scoped sync GET.
            dao.saveRead(scope.owner, readRecord(scope.owner, stableRecordId("lesson-read:" + lesson.lessonId), "lesson_read",
                learningJson.encodeToJsonElement(WirePersonalLessonRecord.serializer(), restored).toString(), restored.state.version))
        } else {
            dao.createLesson(scope.owner, lesson)
            val event = WirePersonalLessonEvent(stableRecordId("lesson-create:" + lesson.lessonId).toString(), "lesson", null,
                learningJson.decodeFromString(WirePersonalLessonInput.serializer(), LearningJson.lesson(lesson).toString()))
            journal.queue(scope.owner, lesson.lessonId, event)
        }
        val progress = dao.restoreDialogue(scope.owner, lesson.lessonId) ?: DialogueProgress.newLesson()
        val value = PracticeSession(scope.owner, lesson.copy(dialogueProgress = progress), questionEn, restored?.state?.domain())
        return lane.withLock {
            operation = null; clock.clear(); session = value
            PracticeResult.Ready(value)
        }
    }

    suspend fun synchronize(scope: LearningCallScope): PracticeResult {
        val current = lane.withLock { session } ?: return PracticeResult.Superseded
        if (current.owner != scope.owner || !isCurrent(scope.owner, current.lesson.lessonId, current.lesson.contentRef)) return PracticeResult.Superseded
        val synced = journal.flush(scope, setOf(current.lesson.lessonId))
        if (synced is LearningReply.Failed) return PracticeResult.Failed(synced.code)
        val rows = journal.records(scope)
        if (rows is LearningReply.Failed) return PracticeResult.Failed(rows.code)
        val record = (rows as LearningReply.Stored).value.filterIsInstance<WirePersonalReadLesson>()
            .singleOrNull { it.recordId == current.lesson.lessonId.toString() } ?: return PracticeResult.Failed("LESSON_NOT_FOUND")
        if (record.payload.contentRef != current.lesson.contentRef.commonWire()) return PracticeResult.Failed("INVALID_RESPONSE")
        dao.saveRead(scope.owner, readRecord(scope.owner, stableRecordId("lesson-read:${current.lesson.lessonId}"), "lesson_read",
            learningJson.encodeToJsonElement(WirePersonalLessonRecord.serializer(), record.payload).toString(), record.version))
        if (!isCurrent(scope.owner, current.lesson.lessonId, current.lesson.contentRef)) return PracticeResult.Superseded
        return lane.withLock { session = session?.copy(lesson = current.lesson.copy(helpReceived = HelpSnapshot(record.payload.helpReceived.map { HelpKind.valueOf(it.name) }.toSet(), record.payload.supportRefs.toSet()), dialogueProgress = DialogueProgress(record.payload.dialogueProgress.hintMethodAnnounced)), progress = record.payload.state.domain()); PracticeResult.Ready(requireNotNull(session)) }
    }

    suspend fun persistHintCompletion(owner: OwnerContext, event: DialogueProgressEvent) { dao.persistHintCompletion(owner, event) }

    suspend fun preparedFeedbackSupports(owner: OwnerContext, attemptId: UUID): List<SupportArtifact> {
        val result = dao.record(owner.profileId.toString(), owner.privacyEpoch, stableRecordId("attempt-result:$attemptId").toString()) ?: return emptyList()
        val value = learningJson.decodeFromString(WireInferenceAttemptResult.serializer(), result.payloadJson)
        return value.feedback?.preparedSupports?.map { it.domain() } ?: emptyList()
    }

    suspend fun resumeLesson(scope: LearningCallScope): PracticeResult {
        val current = lane.withLock { session } ?: return PracticeResult.Superseded
        val progress = current.progress ?: return PracticeResult.Failed("NO_SERVER_STATE")
        if (progress.phase != "suspended" || progress.pendingAttemptId != null || progress.pendingFeedbackAttemptId != null) return PracticeResult.Ready(current)
        val dialogue = dao.restoreDialogue(scope.owner, current.lesson.lessonId) ?: current.lesson.dialogueProgress
        val value = WirePersonalLessonInput(current.lesson.lessonId.toString(), current.lesson.contentRef.commonWire(), "question_wait",
            current.lesson.helpReceived.kinds.map { WireCommonHelpKind.valueOf(it.name) }, current.lesson.helpReceived.refs.toList(),
            current.lesson.returnCheckpointId?.toString(), WireCommonDialogueProgress(dialogue.hintMethodAnnounced))
        journal.queue(scope.owner, current.lesson.lessonId, WirePersonalLessonEvent(stableRecordId("lesson-resume:${current.lesson.lessonId}:${progress.version}").toString(), "lesson", progress.version, value))
        return synchronize(scope)
    }

    suspend fun suspendLesson(scope: LearningCallScope): LearningReply<Unit> {
        val current = lane.withLock { session } ?: return LearningReply.Failed("NO_LESSON", false)
        val progress = current.progress ?: return LearningReply.Failed("NO_SERVER_STATE", false)
        if (progress.phase == "completed") return LearningReply.Stored(scope, Unit)
        val value = WirePersonalLessonInput(current.lesson.lessonId.toString(), current.lesson.contentRef.commonWire(), "suspended",
            current.lesson.helpReceived.kinds.map { WireCommonHelpKind.valueOf(it.name) }, current.lesson.helpReceived.refs.toList(),
            current.lesson.returnCheckpointId?.toString(), WireCommonDialogueProgress(current.lesson.dialogueProgress.hintMethodAnnounced))
        val event = WirePersonalLessonEvent(stableRecordId("lesson-suspend:${current.lesson.lessonId}:${progress.version}").toString(), "lesson", progress.version, value)
        journal.queue(scope.owner, current.lesson.lessonId, event)
        return journal.flush(scope, setOf(current.lesson.lessonId))
    }

    suspend fun interrupt() = lane.withLock { operation = null; clock.clear() }

    /** Use inside SessionCoordinator.acceptFinal's local transaction callback, before networking. */
    suspend fun persistAccepted(answer: AcceptedAnswer) {
        val value = lane.withLock { requireNotNull(session) }
        require(answer.lease.owner == value.owner && answer.lease.task == value.lesson.contentRef && answer.lease.lessonId == value.lesson.lessonId)
        val progress = value.progress
        val turn = if (progress == null) AttemptTurn.FIRST else requireNotNull(progress.nextTurn)
        store.acceptAnswer(answer, LearningJson.wire(turn),
            if (turn == AttemptTurn.RETRY) requireNotNull(progress?.validFirstAttemptId) else null)
    }

    suspend fun submit(scope: LearningCallScope, attemptId: UUID, channel: InteractionChannel, nowMs: Long = elapsedMs()): PracticeResult {
        val current = lane.withLock { session } ?: return PracticeResult.Superseded
        if (current.owner != scope.owner || !isCurrent(scope.owner, current.lesson.lessonId, current.lesson.contentRef)) return PracticeResult.Superseded
        val row = dao.record(scope.owner.profileId.toString(), scope.owner.privacyEpoch, attemptId.toString())
            ?: return PracticeResult.Failed("NOT_ACCEPTED")
        require(row.kind == "attempt" && row.payloadSchemaVersion == 5)
        val input = learningJson.decodeFromString(WirePersonalAttemptInput.serializer(), row.payloadJson)
        require(input.lessonId == current.lesson.lessonId.toString() && input.contentRef == current.lesson.contentRef.commonWire())
        val op = stableRecordId("feedback:" + attemptId)
        if (!begin(op, nowMs)) return PracticeResult.Busy
        try {
            when (val synced = journal.flush(scope, setOf(current.lesson.lessonId, attemptId))) {
                is LearningReply.Failed -> return finishFailure(op, scope, current, synced.code, attemptId)
                is LearningReply.Stored -> Unit
            }
            val requestId = stableRecordId("feedback-request:" + attemptId)
            val old = dao.record(scope.owner.profileId.toString(), scope.owner.privacyEpoch, requestId.toString())
            val request = if (old != null) learningJson.decodeFromString(WireInferenceFeedbackRequest.serializer(), old.payloadJson) else {
                WireInferenceFeedbackRequest(schemaVersion = "5", operationId = op.toString(), interactionChannel = LearningJson.wire(channel),
                    clientContext = scope.owner.contextWire(), attemptId = input.attemptId, contentRef = input.contentRef,
                    answerText = input.answerText, capture = input.capture, helpReceived = input.helpReceived.map { LearningJson.wire(it) },
                    supportRefs = input.supportRefs, retryOfAttemptId = input.previousAttemptId, lessonId = input.lessonId).also {
                    dao.saveRead(scope.owner, readRecord(scope.owner, requestId, "feedback_request",
                        learningJson.encodeToJsonElement(WireInferenceFeedbackRequest.serializer(), it).toString(), 1))
                }
            }
            // Retry reuses original context/body. A new UI context uses GET, never rewrites this request.
            if (request.clientContext != scope.owner.contextWire()) return finishFailure(op, scope, current, "RESTORE_REQUIRED", attemptId)
            return when (val reply = api.evaluateSpeakingAttempt(scope, request)) {
                is LearningReply.Failed -> finishFailure(op, scope, current, reply.code, attemptId)
                is LearningReply.Stored -> {
                    val value = reply.value
                    if (value.lessonState.lessonId != input.lessonId) return finishFailure(op, scope, current, "INVALID_RESPONSE", attemptId)
                    val stored = value.asStoredResult(scope.owner)
                    persistResult(scope.owner, stored)
                    finishResult(op, scope, current, stored)
                }
            }
        } catch (error: CancellationException) { clear(op); throw error }
        catch (error: Exception) { clear(op); throw error }
    }

    /** Fetch persisted results only, including completed-but-unpresented feedback. */
    suspend fun restore(scope: LearningCallScope, attemptId: UUID, nowMs: Long = elapsedMs()): PracticeResult {
        val current = lane.withLock { session } ?: return PracticeResult.Superseded
        if (current.owner != scope.owner || !isCurrent(scope.owner, current.lesson.lessonId, current.lesson.contentRef)) return PracticeResult.Superseded
        val op = UUID.randomUUID()
        if (!begin(op, nowMs)) return PracticeResult.Busy
        try {
            return when (val reply = api.readAttemptResult(scope, attemptId)) {
                is LearningReply.Failed -> finishFailure(op, scope, current, reply.code, attemptId)
                is LearningReply.Stored -> {
                    if (reply.value.contentRef != current.lesson.contentRef.commonWire() || reply.value.lessonState.lessonId != current.lesson.lessonId.toString())
                        return finishFailure(op, scope, current, "INVALID_RESPONSE", attemptId)
                    persistResult(scope.owner, reply.value)
                    finishResult(op, scope, current, reply.value)
                }
            }
        } catch (error: CancellationException) { clear(op); throw error }
        catch (error: Exception) { clear(op); throw error }
    }

    suspend fun declineRetry(scope: LearningCallScope): LearningReply<WirePersonalLessonCommandReceipt> {
        val current = lane.withLock { session } ?: return LearningReply.Failed("NO_LESSON", false)
        val progress = current.progress ?: return LearningReply.Failed("NO_SERVER_STATE", false)
        val feedback = current.feedback ?: return LearningReply.Failed("NO_FEEDBACK", false)
        if (current.owner != scope.owner || progress.phase != "retry_offer" || !isCurrent(scope.owner, current.lesson.lessonId, current.lesson.contentRef))
            return LearningReply.Failed("CONTEXT_CHANGED", false)
        val request = WirePersonalLessonFinishRequest("5", stableRecordId("decline:" + current.lesson.lessonId + ":" + progress.version).toString(),
            scope.owner.profileWire(), progress.version, feedback.attemptId.toString(), "retry_declined")
        val saved = saveLocalCommand(scope.owner, request.operationId, learningJson.encodeToString(WirePersonalLessonFinishRequest.serializer(), request))
        val original = learningJson.decodeFromString(WirePersonalLessonFinishRequest.serializer(), saved)
        return saveCommand(scope, current, api.declineLessonRetry(scope, original, current.lesson.lessonId), original.operationId, saved)
    }

    suspend fun acknowledgeFeedback(scope: LearningCallScope, witness: FeedbackPresentationWitness): LearningReply<WirePersonalLessonCommandReceipt> {
        val current = lane.withLock { session } ?: return LearningReply.Failed("NO_LESSON", false)
        val progress = current.progress ?: return LearningReply.Failed("NO_SERVER_STATE", false)
        if (current.owner != scope.owner || witness.owner != scope.owner || witness.lessonId != current.lesson.lessonId ||
            progress.pendingFeedbackAttemptId != witness.attemptId || !isCurrent(scope.owner, current.lesson.lessonId, current.lesson.contentRef) ||
            !presentationVerifier.verify(witness)) return LearningReply.Failed("PRESENTATION_NOT_VERIFIED", false)
        // Optional examples use their own observed ticket path in PresentationRepository.
        val request = WirePersonalLessonPresentationRequest("5", stableRecordId("feedback-ack:${current.lesson.lessonId}:${witness.attemptId}:${progress.version}:${witness.mode}").toString(),
            scope.owner.profileWire(), progress.version, witness.attemptId.toString(), witness.mode, emptyList())
        val saved = saveLocalCommand(scope.owner, request.operationId, learningJson.encodeToString(WirePersonalLessonPresentationRequest.serializer(), request))
        val original = learningJson.decodeFromString(WirePersonalLessonPresentationRequest.serializer(), saved)
        return saveCommand(scope, current, api.acknowledgeFeedbackPresentation(scope, original, current.lesson.lessonId), original.operationId, saved)
    }

    private suspend fun saveLocalCommand(owner: OwnerContext, operation: String, payload: String): String {
        val old = dao.record(owner.profileId.toString(), owner.privacyEpoch, operation)
        if (old != null) { require(old.kind == "lesson_command" && old.payloadJson == payload); return old.payloadJson }
        dao.saveRead(owner, readRecord(owner, UUID.fromString(operation), "lesson_command", payload, 1))
        return payload
    }

    private suspend fun saveCommand(scope: LearningCallScope, current: PracticeSession,
        reply: LearningReply<WirePersonalLessonCommandReceipt>, operation: String, payload: String): LearningReply<WirePersonalLessonCommandReceipt> {
        if (reply is LearningReply.Stored) {
            if (reply.value.lesson.lessonId != current.lesson.lessonId.toString() || reply.value.lesson.contentRef != current.lesson.contentRef.commonWire())
                return LearningReply.Failed("INVALID_RESPONSE", false)
            dao.saveRead(scope.owner, readRecord(scope.owner, stableRecordId("lesson-read:" + current.lesson.lessonId), "lesson_read",
                learningJson.encodeToJsonElement(WirePersonalLessonRecord.serializer(), reply.value.lesson).toString(), reply.value.lesson.state.version))
            dao.acknowledgeLocalCommand(scope.owner.profileId.toString(), scope.owner.privacyEpoch, operation, payload)
            if (isCurrent(scope.owner, current.lesson.lessonId, current.lesson.contentRef)) lane.withLock {
                if (session?.owner == scope.owner && session?.lesson?.lessonId == current.lesson.lessonId &&
                    (session?.progress?.version ?: 0) <= reply.value.lesson.state.version)
                    session = session?.copy(progress = reply.value.lesson.state.domain())
            }
        }
        return reply
    }

    private suspend fun persistResult(owner: OwnerContext, result: WireInferenceAttemptResult) {
        dao.saveAttemptResult(owner, stableRecordId("attempt-result:" + result.attemptId).toString(),
            learningJson.encodeToJsonElement(WireInferenceAttemptResult.serializer(), result).toString())
    }
    private suspend fun begin(id: UUID, nowMs: Long): Boolean = lane.withLock {
        if (operation != null) false else { operation = id; clock.start(nowMs); true }
    }
    private suspend fun clear(id: UUID): Boolean = lane.withLock {
        if (operation != id) false else { operation = null; clock.clear(); true }
    }
    private suspend fun finishFailure(id: UUID, scope: LearningCallScope, original: PracticeSession, code: String, attempt: UUID): PracticeResult {
        if (!clear(id) || !isCurrent(scope.owner, original.lesson.lessonId, original.lesson.contentRef)) return PracticeResult.Superseded
        lane.withLock {
            if (session?.owner != scope.owner || session?.lesson?.lessonId != original.lesson.lessonId) return PracticeResult.Superseded
            session = session?.copy(requestState = PracticeRequestState.FAILED)
        }
        return PracticeResult.Failed(code, attempt)
    }
    private suspend fun finishResult(id: UUID, scope: LearningCallScope, original: PracticeSession, result: WireInferenceAttemptResult): PracticeResult {
        val expired = lane.withLock { operation == id && clock.state(elapsedMs()) == PracticeRequestState.WAIT_EXPIRED }
        if (!clear(id) || !isCurrent(scope.owner, original.lesson.lessonId, original.lesson.contentRef)) return PracticeResult.Superseded
        if (!mayProjectResult(scope.owner, original.lesson.lessonId, UUID.fromString(result.attemptId))) return PracticeResult.Superseded
        val input = dao.record(scope.owner.profileId.toString(), scope.owner.privacyEpoch, result.attemptId)
            ?.takeIf { it.kind == "attempt" && it.payloadSchemaVersion == 5 }
            ?.let { learningJson.decodeFromString(WirePersonalAttemptInput.serializer(), it.payloadJson) }
        val formConfirmed = input?.capture?.let { it.formFidelity != "unknown" && it.uncertainSpans.isEmpty() } == true
        val feedback = if (expired) null else result.feedback?.safeFeedback(result.lessonState, formConfirmed)
        lane.withLock {
            if (session?.owner != scope.owner || session?.lesson?.lessonId != original.lesson.lessonId) return PracticeResult.Superseded
            if ((session?.progress?.version ?: 0) > result.lessonState.version) return PracticeResult.Superseded
            session = session?.copy(progress = result.lessonState.domain(), feedback = feedback,
                requestState = if (expired) PracticeRequestState.WAIT_EXPIRED else if (feedback == null) PracticeRequestState.DEVICE_ONLY else PracticeRequestState.STORED)
        }
        return PracticeResult.Saved(UUID.fromString(result.attemptId), feedback, expired || result.status == WireCommonOperationStatus.PENDING)
    }
}

internal fun PracticeRef.commonWire(): WireCommonPracticeContentRef {
    require(this is PracticeContentRef) { "Prepared source requires the strict source6 adapter" }
    return learningJson.decodeFromString(WireCommonPracticeContentRef.serializer(), LearningJson.practice(this).toString())
}
internal fun WireCommonLessonState.domain() = PracticeProgress(version, validFirstAttemptId?.let(UUID::fromString), validRetryAttemptId?.let(UUID::fromString),
    pendingAttemptId?.let(UUID::fromString), pendingFeedbackAttemptId?.let(UUID::fromString), phase, contentSuccess, retryOutcome, endReason)
private fun WireInferenceStoredFeedback.safeFeedback(state: WireCommonLessonState, formConfirmed: Boolean) = PracticeFeedback(UUID.fromString(attemptId),
    Judgement.valueOf(judgement.uppercase(Locale.ROOT)), focus, feedbackKo,
    null, // Exact optional corrections are opened only through the registered support presenter.
    EnglishAttemptStatus.valueOf(englishAttemptStatus.name), state.domain())
private fun WireInferenceFeedbackResponse.asStoredResult(owner: OwnerContext): WireInferenceAttemptResult {
    val transient = learningJson.encodeToJsonElement(WireInferenceFeedbackResponse.serializer(), this).jsonObject
    val stored = JsonObject(transient.filterKeys { it !in setOf("schema_version", "operation_id", "client_context", "lesson_state", "example_exposure") })
    return WireInferenceAttemptResult(profileContext = owner.profileWire(), attemptId = attemptId, contentRef = contentRef,
        status = WireCommonOperationStatus.SUCCEEDED, feedback = learningJson.decodeFromJsonElement(WireInferenceStoredFeedback.serializer(), stored), schemaVersion = "5",
        lessonState = lessonState, exampleExposure = exampleExposure, englishAttemptStatus = englishAttemptStatus)
}
