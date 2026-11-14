package io.github.driveenglishlab.pleos.data

import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.*

/** Protocol/storage adapter for the existing practice reducer. It never decides a turn,
 * captures input, starts output, or treats a persisted response as presentation.
 * The caller retains the same PracticeRequestClock and current-capture/session guard. */
class Source6PracticeRepository(
    private val api: IncrementalLearningApi,
    private val dao: ReviewDao,
    private val codecs: IncrementalContractJson,
    private val review: ReviewRepository,
    private val isCurrent: suspend (OwnerContext, UUID, PracticeRef) -> Boolean,
    private val presentationVerifier: FeedbackPresentationVerifier = FeedbackPresentationVerifier { false },
    private val mayProjectResult: suspend (OwnerContext, UUID, UUID) -> Boolean = { _, _, _ -> true },
) {
    private val lane = Mutex()

    suspend fun open(scope: LearningCallScope, lesson: LessonInput, questionEn: String,
        restored: WireSource6PersonalLessonRecord? = null): PracticeResult {
        require(lesson.contentRef is PreparedPracticeRef || lesson.contentRef is PracticeContentRef)
        if (!active(scope, lesson)) return PracticeResult.Superseded
        if (restored != null) {
            require(restored.lessonId == lesson.lessonId.toString() && restored.contentRef == lesson.contentRef.source6Wire())
            saveLesson(scope, restored)
            return PracticeResult.Ready(project(PracticeSession(scope.owner, lesson, questionEn, null), restored))
        }
        val event = WireSource6PersonalLessonEvent(stableRecordId("source6-lesson-create:${lesson.lessonId}").toString(), "lesson", null, lesson.source6Wire())
        queue(scope.owner, "source6_event", lesson.lessonId.toString(), "PersonalSyncEvent", event, WireSource6PersonalSyncEvent.serializer())
        return synchronize(scope, PracticeSession(scope.owner, lesson, questionEn, null))
    }

    suspend fun synchronize(scope: LearningCallScope, current: PracticeSession): PracticeResult {
        if (!active(scope, current.lesson) || current.owner != scope.owner) return PracticeResult.Superseded
        when (val synced = flush(scope, current.lesson.lessonId)) {
            is LearningReply.Failed -> return PracticeResult.Failed(synced.code)
            is LearningReply.Stored -> Unit
        }
        var cursor: String? = null
        var found: WireSource6PersonalLessonRecord? = null
        repeat(100) {
            when (val page = api.call("source6", "/v2/profile/sync", "get", scope, null, WireSource6PersonalSyncPage.serializer(),
                queryArguments = mapOf("client_privacy_epoch" to scope.owner.privacyEpoch.toString(), "cursor" to cursor, "limit" to "50"))) {
                is LearningReply.Failed -> return PracticeResult.Failed(page.code)
                is LearningReply.Stored -> {
                    if (page.value.resetRequired) return PracticeResult.Superseded
                    page.value.records.filterIsInstance<WireSource6PersonalReadLesson>().filter { it.recordId == current.lesson.lessonId.toString() }
                        .forEach { if (found == null || it.version > found!!.state.version) found = it.payload }
                    val next = page.value.cursor
                    if (page.value.records.size < 50 || next == null || next == cursor) {
                        val record = found ?: return PracticeResult.Failed("LESSON_NOT_FOUND")
                        if (record.contentRef != current.lesson.contentRef.source6Wire()) return PracticeResult.Failed("INVALID_RESPONSE")
                        saveLesson(scope, record)
                        if (!active(scope, current.lesson)) return PracticeResult.Superseded
                        return PracticeResult.Ready(project(current, record))
                    }
                    cursor = next
                }
            }
        }
        return PracticeResult.Failed("SYNC_NOT_READY")
    }

    /** Called inside SessionCoordinator.acceptFinal's local transaction callback.
     * Both immutable input and the exact local assistance frontier commit together. */
    suspend fun persistAccepted(answer: AcceptedAnswer, current: PracticeSession, deviceStreamId: UUID, throughSequence: Long) {
        require(answer.lease.owner == current.owner && answer.lease.lessonId == current.lesson.lessonId && answer.lease.task == current.lesson.contentRef)
        require(current.lesson.contentRef is PreparedPracticeRef || current.lesson.contentRef is PracticeContentRef)
        val progress = requireNotNull(current.progress) { "Authoritative lesson required before accepting input." }
        val turn = requireNotNull(progress.nextTurn)
        val input = WireSource6PersonalAttemptInput(answer.lease.attemptId.toString(), answer.lease.lessonId.toString(),
            answer.lease.task.source6Wire(), LearningJson.wire(turn), if (turn == AttemptTurn.RETRY) requireNotNull(progress.validFirstAttemptId).toString() else null,
            answer.text, answer.capture.source6Wire(), answer.helpReceived.kinds.sortedBy { it.name }.map { WireSource6CommonHelpKind.valueOf(it.name) }, answer.helpReceived.refs.sorted())
        val attempt = operation(answer.lease.owner, answer.lease.attemptId.toString(), "source6_attempt", current.lesson.lessonId.toString(),
            "PersonalAttemptInput", input, WireSource6PersonalAttemptInput.serializer())
        val fence = WireReviewAssistanceFenceRequest(1, current.owner.profileId.toString(), current.owner.privacyEpoch,
            stableRecordId("source6-assistance-fence:${answer.lease.attemptId}").toString(), answer.lease.attemptId.toString(), deviceStreamId.toString(), throughSequence, attempt.payloadHash)
        val fenceJson = incrementalCanonical(codecs.parse("review", codecs.encode("review", "AssistanceFenceRequest", fence, WireReviewAssistanceFenceRequest.serializer())))
        val fenceRow = LocalReviewOperation(fence.operationId, attempt.profileId, attempt.privacyEpoch, attempt.ownerRevision, "fence", input.attemptId, fenceJson, LearningJson.hash(fenceJson))
        dao.queueAttemptAndFence(current.owner, attempt, fenceRow, deviceStreamId.toString(), throughSequence)
    }

    suspend fun submit(scope: LearningCallScope, current: PracticeSession, attemptId: UUID, channel: InteractionChannel,
        waitExpired: () -> Boolean = { false }): PracticeResult {
        if (!lane.tryLock()) return PracticeResult.Busy
        try {
            if (!active(scope, current.lesson) || current.owner != scope.owner) return PracticeResult.Superseded
            val row = dao.operation(attemptId.toString()) ?: return PracticeResult.Failed("NOT_ACCEPTED")
            require(row.kind == "source6_attempt" && owned(row, scope.owner))
            val input = codecs.decodeSource6("PersonalAttemptInput", row.payloadJson, WireSource6PersonalAttemptInput.serializer())
            require(input.lessonId == current.lesson.lessonId.toString() && input.contentRef == current.lesson.contentRef.source6Wire())
            val fenceRow = dao.operation(stableRecordId("source6-assistance-fence:$attemptId").toString()) ?: return PracticeResult.Failed("ASSISTANCE_FENCE_MISSING", attemptId)
            val fence = codecs.decodeReview("AssistanceFenceRequest", fenceRow.payloadJson, WireReviewAssistanceFenceRequest.serializer())
            when (val observations = review.flushPendingObservations(scope, fence.deviceStreamId, fence.throughSequence)) {
                is LearningReply.Failed -> return PracticeResult.Failed(observations.code, attemptId)
                is LearningReply.Stored -> Unit
            }
            when (val fenced = review.fence(scope, fence)) {
                is LearningReply.Failed -> return PracticeResult.Failed(fenced.code, attemptId)
                is LearningReply.Stored -> if (fenced.value.state != "ready") return PracticeResult.Failed("ASSISTANCE_FENCE_NOT_READY", attemptId)
            }
            when (val synced = flush(scope, current.lesson.lessonId)) {
                is LearningReply.Failed -> return PracticeResult.Failed(synced.code, attemptId)
                is LearningReply.Stored -> Unit
            }
            val operationId = stableRecordId("feedback:$attemptId")
            val requestRow = dao.operation(operationId.toString()) ?: queue(scope.owner, "source6_feedback_request", attemptId.toString(), "InferenceFeedbackRequest",
                WireSource6InferenceFeedbackRequest("6", operationId.toString(), LearningJson.wire(channel), scope.owner.source6Context(), input.attemptId,
                    input.contentRef, input.answerText, input.capture, input.helpReceived.map { LearningJson.wire(it) }, input.supportRefs, input.previousAttemptId, input.lessonId),
                WireSource6InferenceFeedbackRequest.serializer(), operationId.toString())
            val request = codecs.decodeSource6("InferenceFeedbackRequest", requestRow.payloadJson, WireSource6InferenceFeedbackRequest.serializer())
            if (request.clientContext != scope.owner.source6Context()) return PracticeResult.Failed("RESTORE_REQUIRED", attemptId)
            if (!active(scope, current.lesson)) return PracticeResult.Superseded
            return when (val reply = api.call("source6", "/v2/attempt-feedback", "post", scope,
                api.payload("source6", "InferenceFeedbackRequest", request, WireSource6InferenceFeedbackRequest.serializer()), WireSource6InferenceFeedbackResponse.serializer())) {
                is LearningReply.Failed -> PracticeResult.Failed(reply.code, attemptId)
                is LearningReply.Stored -> {
                    val result = reply.value.stored(scope.owner)
                    saveResult(scope, result)
                    projectResult(scope, current, result, waitExpired())
                }
            }
        } finally { lane.unlock() }
    }

    /** GET restores stored data; it neither resubmits the input nor acknowledges output. */
    suspend fun restore(scope: LearningCallScope, current: PracticeSession, attemptId: UUID): PracticeResult {
        if (current.owner != scope.owner || !active(scope, current.lesson)) return PracticeResult.Superseded
        return when (val reply = api.call("source6", "/v2/attempts/{attempt_id}", "get", scope, null,
            WireSource6InferenceAttemptResult.serializer(), mapOf("attempt_id" to attemptId.toString()))) {
            is LearningReply.Failed -> PracticeResult.Failed(reply.code, attemptId)
            is LearningReply.Stored -> { saveResult(scope, reply.value); projectResult(scope, current, reply.value, false) }
        }
    }

    suspend fun declineRetry(scope: LearningCallScope, current: PracticeSession): LearningReply<WireSource6PersonalLessonCommandReceipt> {
        val progress = current.progress ?: return LearningReply.Failed("NO_SERVER_STATE", false)
        val feedback = current.feedback ?: return LearningReply.Failed("NO_FEEDBACK", false)
        if (progress.phase != "retry_offer" || !active(scope, current.lesson)) return LearningReply.Failed("CONTEXT_CHANGED", false)
        val request = WireSource6PersonalLessonFinishRequest("6", stableRecordId("decline:${current.lesson.lessonId}:${progress.version}").toString(),
            scope.owner.source6Profile(), progress.version, feedback.attemptId.toString(), "retry_declined")
        return command(scope, current, request.operationId, "PersonalLessonFinishRequest", request, WireSource6PersonalLessonFinishRequest.serializer(), "finish")
    }

    suspend fun acknowledgeFeedback(scope: LearningCallScope, current: PracticeSession, witness: FeedbackPresentationWitness): LearningReply<WireSource6PersonalLessonCommandReceipt> {
        val progress = current.progress ?: return LearningReply.Failed("NO_SERVER_STATE", false)
        if (witness.owner != scope.owner || witness.lessonId != current.lesson.lessonId || witness.attemptId != progress.pendingFeedbackAttemptId ||
            !active(scope, current.lesson) || !presentationVerifier.verify(witness)) return LearningReply.Failed("PRESENTATION_NOT_VERIFIED", false)
        val request = WireSource6PersonalLessonPresentationRequest("6", stableRecordId("feedback-ack:${current.lesson.lessonId}:${witness.attemptId}:${progress.version}:${witness.mode}").toString(),
            scope.owner.source6Profile(), progress.version, witness.attemptId.toString(), witness.mode, emptyList())
        return command(scope, current, request.operationId, "PersonalLessonPresentationRequest", request, WireSource6PersonalLessonPresentationRequest.serializer(), "feedback-presentations")
    }

    suspend fun resumeLesson(scope: LearningCallScope, current: PracticeSession): PracticeResult {
        val progress = current.progress ?: return PracticeResult.Failed("NO_SERVER_STATE")
        if (progress.phase != "suspended" || progress.pendingAttemptId != null || progress.pendingFeedbackAttemptId != null) return PracticeResult.Ready(current)
        if (!active(scope, current.lesson)) return PracticeResult.Superseded
        val value = current.lesson.copy(phase = LessonInputPhase.QUESTION_WAIT).source6Wire()
        val event = WireSource6PersonalLessonEvent(stableRecordId("source6-lesson-resume:${current.lesson.lessonId}:${progress.version}").toString(), "lesson", progress.version, value)
        queue(scope.owner, "source6_event", current.lesson.lessonId.toString(), "PersonalSyncEvent", event, WireSource6PersonalSyncEvent.serializer())
        return synchronize(scope, current)
    }

    suspend fun suspendLesson(scope: LearningCallScope, current: PracticeSession): LearningReply<Unit> {
        val progress = current.progress ?: return LearningReply.Failed("NO_SERVER_STATE", false)
        if (!active(scope, current.lesson)) return LearningReply.Failed("CONTEXT_CHANGED", false)
        if (progress.phase == "completed") return LearningReply.Stored(scope, Unit)
        val value = current.lesson.copy(phase = LessonInputPhase.SUSPENDED).source6Wire()
        val event = WireSource6PersonalLessonEvent(stableRecordId("source6-lesson-suspend:${current.lesson.lessonId}:${progress.version}").toString(), "lesson", progress.version, value)
        queue(scope.owner, "source6_event", current.lesson.lessonId.toString(), "PersonalSyncEvent", event, WireSource6PersonalSyncEvent.serializer())
        return flush(scope, current.lesson.lessonId)
    }

    suspend fun persistHintCompletion(owner: OwnerContext, event: DialogueProgressEvent) {
        val observed = event.observation
        val value = WireSource6PersonalDialogueProgressEvent(event.eventId.toString(), "dialogue_progress", JsonNull,
            WireSource6PersonalDialogueProgressObservation(observed.lessonId.toString(), observed.hintMethodAnnounced, observed.outputInstanceId.toString(), observed.adapterEvidenceRef))
        queue(owner, "source6_event", observed.lessonId.toString(), "PersonalSyncEvent", value, WireSource6PersonalSyncEvent.serializer())
    }

    suspend fun savedResult(owner: OwnerContext, attemptId: UUID): WireSource6InferenceAttemptResult? {
        val row = dao.source6Record(owner.profileId.toString(), owner.privacyEpoch, "attempt_result", attemptId.toString()) ?: return null
        require(row.payloadHash == LearningJson.hash(row.payloadJson))
        return codecs.decodeSource6("InferenceAttemptResult", row.payloadJson, WireSource6InferenceAttemptResult.serializer())
    }

    suspend fun preparedFeedbackSupports(owner: OwnerContext, attemptId: UUID): List<SupportArtifact> =
        savedResult(owner, attemptId)?.feedback?.preparedSupports?.map { value ->
            require(LearningJson.hash(value.text) == value.textHash)
            SupportArtifact(value.supportRef, value.kind?.let { HelpKind.valueOf(it.name) }, SupportRole.valueOf(value.role.uppercase(Locale.ROOT)),
                SupportLanguage.valueOf(value.language.uppercase(Locale.ROOT)), value.text, value.textHash, SupportOrigin.valueOf(value.origin.uppercase(Locale.ROOT)),
                value.feedbackAttemptId?.let(UUID::fromString))
        } ?: emptyList()

    private suspend fun <T> command(scope: LearningCallScope, current: PracticeSession, id: String, definition: String, value: T,
        serializer: SerializationStrategy<T>, endpoint: String): LearningReply<WireSource6PersonalLessonCommandReceipt> {
        if (current.owner != scope.owner || !active(scope, current.lesson)) return LearningReply.Failed("CONTEXT_CHANGED", false)
        val row = queue(scope.owner, "source6_command", current.lesson.lessonId.toString(), definition, value, serializer, id)
        val reply = api.call("source6", "/v2/lessons/{lesson_id}/$endpoint", "post", scope, codecs.parse("source6", row.payloadJson),
            WireSource6PersonalLessonCommandReceipt.serializer(), mapOf("lesson_id" to current.lesson.lessonId.toString()))
        if (reply is LearningReply.Stored) {
            if (reply.value.lesson.contentRef != current.lesson.contentRef.source6Wire() || reply.value.lesson.lessonId != current.lesson.lessonId.toString()) return LearningReply.Failed("INVALID_RESPONSE", false)
            saveLesson(scope, reply.value.lesson)
            dao.acknowledge(scope.owner, row, codecs.encode("source6", "PersonalLessonCommandReceipt", reply.value, WireSource6PersonalLessonCommandReceipt.serializer()))
        }
        return reply
    }

    /** Foreground reconciliation of already accepted immutable input only. No model,
     * feedback command, new attempt, active-resume selection or output is requested. */
    suspend fun flushAccepted(scope: LearningCallScope): LearningReply<Unit> {
        if (!lane.tryLock()) return LearningReply.Failed("SYNC_BUSY", true)
        try {
            if (!api.isCurrent(scope)) return LearningReply.Failed("SESSION_CHANGED", false)
            val pending = dao.pending(scope.owner.profileId.toString(), scope.owner.privacyEpoch, scope.owner.revision)
                .filter { it.kind in setOf("source6_event", "source6_attempt") }
            val lessons = pending.map { requireNotNull(it.targetId) }.distinct()
            for (lesson in lessons) {
                if (!api.isCurrent(scope)) return LearningReply.Failed("SESSION_CHANGED", false)
                for (attempt in pending.filter { it.targetId == lesson && it.kind == "source6_attempt" }) {
                    require(owned(attempt, scope.owner) && attempt.status == "pending" && LearningJson.hash(attempt.payloadJson) == attempt.payloadHash)
                    val input = codecs.decodeSource6("PersonalAttemptInput", attempt.payloadJson, WireSource6PersonalAttemptInput.serializer())
                    require(input.attemptId == attempt.operationId && input.lessonId == lesson)
                    val row = dao.operation(stableRecordId("source6-assistance-fence:${attempt.operationId}").toString())
                        ?: return LearningReply.Failed("ASSISTANCE_FENCE_MISSING", false)
                    require(owned(row, scope.owner) && row.kind == "fence" && row.targetId == input.attemptId &&
                        row.status in setOf("pending", "acknowledged") && LearningJson.hash(row.payloadJson) == row.payloadHash)
                    val fence = codecs.decodeReview("AssistanceFenceRequest", row.payloadJson, WireReviewAssistanceFenceRequest.serializer())
                    require(fence.attemptId == input.attemptId && fence.originalRequestHash == attempt.payloadHash)
                    when (val observations = review.flushPendingObservations(scope, fence.deviceStreamId, fence.throughSequence)) {
                        is LearningReply.Failed -> return observations
                        is LearningReply.Stored -> Unit
                    }
                    // An ACKed fence needs no transport. Its fixed frontier/hash was
                    // already admitted; a pending one is retried without changing them.
                    if (row.status == "pending") when (val fenced = review.fence(scope, fence)) {
                        is LearningReply.Failed -> return fenced
                        is LearningReply.Stored -> if (fenced.value.state != "ready") return LearningReply.Failed("ASSISTANCE_FENCE_NOT_READY", true)
                    }
                }
                when (val flushed = flush(scope, UUID.fromString(lesson))) {
                    is LearningReply.Failed -> return flushed
                    is LearningReply.Stored -> Unit
                }
            }
            return LearningReply.Stored(scope, Unit)
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { return LearningReply.Failed(if (api.isCurrent(scope)) "INVALID_SYNC_JOURNAL" else "SESSION_CHANGED", false) }
        finally { lane.unlock() }
    }

    private suspend fun flush(scope: LearningCallScope, lessonId: UUID): LearningReply<Unit> {
        while (true) {
            val pending = dao.pending(scope.owner.profileId.toString(), scope.owner.privacyEpoch, scope.owner.revision)
                .filter { it.targetId == lessonId.toString() && it.kind in setOf("source6_event", "source6_attempt") }
            val rows = mutableListOf<LocalReviewOperation>()
            for (row in pending) {
                if (row.kind == "source6_attempt") {
                    val fence = dao.operation(stableRecordId("source6-assistance-fence:${row.operationId}").toString())
                    if (fence?.status != "acknowledged") continue
                }
                rows += row
                if (rows.size == 10) break
            }
            if (rows.isEmpty() && pending.isNotEmpty()) return LearningReply.Failed("ASSISTANCE_FENCE_NOT_READY", true)
            if (rows.isEmpty()) return LearningReply.Stored(scope, Unit)
            val events = rows.map { row ->
                require(row.payloadHash == LearningJson.hash(row.payloadJson))
                if (row.kind == "source6_attempt") WireSource6PersonalAttemptEvent(row.operationId, "attempt", null,
                    codecs.decodeSource6("PersonalAttemptInput", row.payloadJson, WireSource6PersonalAttemptInput.serializer()))
                else codecs.decodeSource6("PersonalSyncEvent", row.payloadJson, WireSource6PersonalSyncEvent.serializer())
            }
            val request = WireSource6PersonalSyncRequest(stableRecordId("source6-sync:" + rows.joinToString(":") { it.operationId + "/" + it.payloadHash }).toString(),
                scope.owner.source6Profile(), events, "6")
            when (val reply = api.call("source6", "/v2/profile/sync", "post", scope,
                api.payload("source6", "PersonalSyncRequest", request, WireSource6PersonalSyncRequest.serializer()), WireSource6PersonalSyncReceipt.serializer())) {
                is LearningReply.Failed -> return reply
                is LearningReply.Stored -> {
                    if (reply.value.receipts.size != rows.size || reply.value.receipts.map { it.eventId }.toSet() != rows.map { it.operationId }.toSet() ||
                        reply.value.receipts.any { it.outcome == "conflict" }) return LearningReply.Failed("SYNC_CONFLICT", false)
                    val receipt = codecs.encode("source6", "PersonalSyncReceipt", reply.value, WireSource6PersonalSyncReceipt.serializer())
                    for (row in rows) dao.acknowledge(scope.owner, row, receipt)
                }
            }
        }
    }

    private suspend fun saveLesson(scope: LearningCallScope, value: WireSource6PersonalLessonRecord) = save(scope, "practice_lesson", value.lessonId, value.state.version,
        "PersonalLessonRecord", value, WireSource6PersonalLessonRecord.serializer())
    private suspend fun saveResult(scope: LearningCallScope, value: WireSource6InferenceAttemptResult) = save(scope, "attempt_result", value.attemptId, value.lessonState.version,
        "InferenceAttemptResult", value, WireSource6InferenceAttemptResult.serializer())
    private suspend fun <T> save(scope: LearningCallScope, kind: String, id: String, version: Long, definition: String, value: T, serializer: SerializationStrategy<T>) {
        val json = incrementalCanonical(codecs.parse("source6", codecs.encode("source6", definition, value, serializer)))
        dao.storeSource6(scope.owner, LocalSource6Record(scope.owner.profileId.toString(), scope.owner.privacyEpoch, kind, id, version, json, LearningJson.hash(json)))
    }
    private fun <T> operation(owner: OwnerContext, id: String, kind: String, target: String?, definition: String, value: T, serializer: SerializationStrategy<T>): LocalReviewOperation {
        val json = incrementalCanonical(codecs.parse("source6", codecs.encode("source6", definition, value, serializer)))
        return LocalReviewOperation(id, owner.profileId.toString(), owner.privacyEpoch, owner.revision, kind, target, json, LearningJson.hash(json))
    }
    private suspend fun <T> queue(owner: OwnerContext, kind: String, target: String?, definition: String, value: T, serializer: SerializationStrategy<T>, id: String? = null): LocalReviewOperation {
        val json = codecs.parse("source6", codecs.encode("source6", definition, value, serializer)).jsonObject
        val operationId = id ?: json["event_id"]!!.jsonPrimitive.content
        return dao.queue(owner, operation(owner, operationId, kind, target, definition, value, serializer))
    }
    private suspend fun active(scope: LearningCallScope, lesson: LessonInput) = api.isCurrent(scope) && isCurrent(scope.owner, lesson.lessonId, lesson.contentRef)
    private fun owned(row: LocalReviewOperation, owner: OwnerContext) = row.profileId == owner.profileId.toString() && row.privacyEpoch == owner.privacyEpoch && row.ownerRevision == owner.revision
    private fun project(current: PracticeSession, record: WireSource6PersonalLessonRecord) = current.copy(lesson = current.lesson.copy(
        helpReceived = HelpSnapshot(record.helpReceived.map { HelpKind.valueOf(it.name) }.toSet(), record.supportRefs.toSet()),
        dialogueProgress = DialogueProgress(record.dialogueProgress.hintMethodAnnounced)), progress = record.state.practiceProgress())
    private suspend fun projectResult(scope: LearningCallScope, current: PracticeSession, result: WireSource6InferenceAttemptResult, expired: Boolean): PracticeResult {
        if (result.contentRef != current.lesson.contentRef.source6Wire() || result.lessonState.lessonId != current.lesson.lessonId.toString()) return PracticeResult.Failed("INVALID_RESPONSE")
        if (!active(scope, current.lesson) || !mayProjectResult(scope.owner, current.lesson.lessonId, UUID.fromString(result.attemptId)) ||
            result.lessonState.version < (current.progress?.version ?: 0)) return PracticeResult.Superseded
        val cached = dao.source6Record(scope.owner.profileId.toString(), scope.owner.privacyEpoch, "attempt_result", result.attemptId)
        if (cached != null && cached.version > result.lessonState.version) return PracticeResult.Superseded
        val feedback = if (expired) null else result.feedback?.let { PracticeFeedback(UUID.fromString(it.attemptId), Judgement.valueOf(it.judgement.uppercase(Locale.ROOT)),
            it.focus, it.feedbackKo, null, EnglishAttemptStatus.valueOf(it.englishAttemptStatus.name), result.lessonState.practiceProgress()) }
        return PracticeResult.Saved(UUID.fromString(result.attemptId), feedback, expired || result.status == WireSource6CommonOperationStatus.PENDING)
    }
}

fun PreparedContentRef.source6Wire() = WireSource6PreparedContentRef6("prepared_components", articleId.toString(), sourceSnapshotId.toString(), sourceHash,
    materialGenerationId.toString(), mainComponentId.toString(), mainScriptId.toString(), mainTextHash, LearningJson.wire(listeningMode))
fun PracticeRef.source6Wire(): WireSource6PracticeContentRef6 {
    if(this is PracticeContentRef)return WireSource6LegacyPracticeRef6("legacy_bundle",WireSource6LegacyPracticeRef5(content.articleId.toString(),content.bundleId.toString(),
        content.sourceSnapshotId.toString(),LearningJson.wire(content.listeningMode),content.listeningScriptId.toString(),taskId.toString(),basicQuestionVersionId.toString()))
    val prepared = this as? PreparedPracticeRef ?: error("Unknown source6 practice reference.")
    val c = prepared.content
    return WireSource6PreparedPracticeRef6("prepared_components", c.articleId.toString(), c.sourceSnapshotId.toString(), c.sourceHash,
        c.materialGenerationId.toString(), c.mainComponentId.toString(), c.mainScriptId.toString(), c.mainTextHash, LearningJson.wire(c.listeningMode),
        prepared.practiceComponentId.toString(), prepared.basicQuestionVersionId.toString(), prepared.taskRevision)
}
fun OwnerContext.source6Profile() = WireSource6CommonProfileContext(profileId.toString(), privacyEpoch)
fun OwnerContext.source6Context() = WireSource6CommonClientContext(profileId.toString(), privacyEpoch, revision, presentationToken.toString())
internal fun Capture.source6Wire() = WireSource6CommonCapture(LearningJson.wire(path), LearningJson.wire(formFidelity), uncertainSpans,
    LearningJson.wire(spokenLanguage), LearningJson.wire(englishEvidence), languageEvidenceRef)
internal fun LessonInput.source6Wire() = WireSource6PersonalLessonInput(lessonId.toString(), contentRef.source6Wire(), LearningJson.wire(phase),
    helpReceived.kinds.sortedBy { it.name }.map { WireSource6CommonHelpKind.valueOf(it.name) }, helpReceived.refs.sorted(), returnCheckpointId?.toString(), WireSource6CommonDialogueProgress(dialogueProgress.hintMethodAnnounced))
fun WireSource6CommonLessonState.practiceProgress() = PracticeProgress(version, validFirstAttemptId?.let(UUID::fromString), validRetryAttemptId?.let(UUID::fromString),
    pendingAttemptId?.let(UUID::fromString), pendingFeedbackAttemptId?.let(UUID::fromString), phase, contentSuccess, retryOutcome, endReason)
private fun WireSource6InferenceFeedbackResponse.stored(owner: OwnerContext): WireSource6InferenceAttemptResult {
    val feedback = WireSource6InferenceStoredFeedback(attemptId, contentRef, judgement, focus, feedbackKo, evidenceRefs, correction, exampleSentence, supportIssued,
        retry, generation, focusFactRefs, retryOutcome, preparedSupports, englishAttemptStatus)
    return WireSource6InferenceAttemptResult(owner.source6Profile(), attemptId, contentRef, WireSource6CommonOperationStatus.SUCCEEDED, feedback, "6", lessonState, exampleExposure, englishAttemptStatus)
}
