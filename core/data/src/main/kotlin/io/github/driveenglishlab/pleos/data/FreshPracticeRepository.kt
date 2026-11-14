package io.github.driveenglishlab.pleos.data

import androidx.room.withTransaction
import io.github.driveenglishlab.pleos.domain.*
import io.github.driveenglishlab.pleos.data.wire.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface FreshPracticeResult {
    /** This is the only result permitting the host to replace its unsubmitted UI draft. */
    data class Selected(val lesson: WireSource6PersonalReadLesson, val discardUnsubmittedUi: Boolean) : FreshPracticeResult
    data class Pending(val operationId: UUID, val newLessonId: String?, val code: String, val retryable: Boolean) : FreshPracticeResult
    data class Conflict(val operationId: UUID, val newLessonId: String?, val code: String) : FreshPracticeResult
}

/** Fresh identity, existing sync admission and existing resume CAS; never a second lesson engine. */
class FreshPracticeRepository(
    private val api: IncrementalLearningApi,
    private val codecs: IncrementalContractJson,
    private val database: LearningDatabase,
    private val listening: Source6ListeningRepository,
    private val review: ReviewRepository,
) {
    private val lane = Mutex()
    private val dao get() = database.review()

    suspend fun prepareAndSelect(scope: LearningCallScope, sourceLesson: WireSource6PersonalLessonRecord,
        expectedResumeVersion: Long, operationId: UUID): FreshPracticeResult = lane.withLock {
        guarded(scope, operationId) {
            if (!api.isCurrent(scope)) return@guarded FreshPracticeResult.Conflict(operationId, null, "SESSION_CHANGED")
            require(expectedResumeVersion >= 0 && expectedResumeVersion < Long.MAX_VALUE)
            UUID.fromString(sourceLesson.lessonId)
            val pinned = codecs.encode("source6", "PracticeContentRef6", sourceLesson.contentRef, WireSource6PracticeContentRef6.serializer())
            val previous = dao.freshIntent(operationId.toString())
            val newId = previous?.newLessonId ?: stable(operationId, "lesson").toString()
            require(newId != sourceLesson.lessonId)
            val identity = "${sourceLesson.lessonId}\n$newId\n$expectedResumeVersion\n$pinned"
            val intent = LocalFreshPracticeIntent(operationId.toString(), scope.owner.profileId.toString(), scope.owner.privacyEpoch,
                scope.owner.revision, sourceLesson.lessonId, newId, expectedResumeVersion, pinned, LearningJson.hash(identity))
            if (previous == null) {
                val sync = WireSource6PersonalSyncRequest(stable(operationId, "sync").toString(), context(scope), listOf(
                    WireSource6PersonalLessonEvent(stable(operationId, "event").toString(), "lesson", null,
                        WireSource6PersonalLessonInput(newId, sourceLesson.contentRef, "question_wait", emptyList(), emptyList(),
                            sourceLesson.returnCheckpointId, WireSource6CommonDialogueProgress(false)))), "6")
                val resume = WireSource6PersonalResumeSelection("6", stable(operationId, "resume").toString(), context(scope),
                    expectedResumeVersion, WireSource6PersonalResumeRef("practice", newId))
                val syncBytes = codecs.encode("source6", "PersonalSyncRequest", sync, WireSource6PersonalSyncRequest.serializer())
                val resumeBytes = codecs.encode("source6", "PersonalResumeSelection", resume, WireSource6PersonalResumeSelection.serializer())
                // A crash cannot leave an intent whose original command/return checkpoint
                // must later be reconstructed from a changed source lesson.
                database.withTransaction {
                    dao.prepareFresh(scope.owner, intent)
                    dao.queueBatch(scope.owner, listOf(
                        operation(scope, sync.operationId, "fresh_practice_sync", newId, syncBytes),
                        operation(scope, resume.operationId, "source6_resume", newId, resumeBytes)))
                }
            } else {
                // Existing progress is preserved, but the immutable caller identity must match.
                dao.prepareFresh(scope.owner, intent)
            }
            continueIntent(scope, operationId)
        }
    }

    /** Explicit retry reads the original new UUID, source ref, sync body and resume CAS. */
    suspend fun retry(scope: LearningCallScope, operationId: UUID): FreshPracticeResult = lane.withLock {
        guarded(scope, operationId) { continueIntent(scope, operationId) }
    }

    private suspend fun continueIntent(scope: LearningCallScope, id: UUID): FreshPracticeResult {
        if (!api.isCurrent(scope)) return FreshPracticeResult.Conflict(id, null, "SESSION_CHANGED")
        var intent = dao.freshIntent(id.toString()) ?: return FreshPracticeResult.Conflict(id, null, "INTENT_NOT_FOUND")
        require(intent.profileId == scope.owner.profileId.toString() && intent.privacyEpoch == scope.owner.privacyEpoch && intent.ownerRevision == scope.owner.revision)
        if (intent.state in setOf("isolated", "conflict")) return FreshPracticeResult.Conflict(id, intent.newLessonId, "FRESH_INTENT_CONFLICT")
        val pinned = codecs.decodeSource6Practice(intent.pinnedRefJson)
        val identity = "${intent.sourceLessonId}\n${intent.newLessonId}\n${intent.expectedResumeVersion}\n${intent.pinnedRefJson}"
        require(intent.payloadHash == LearningJson.hash(identity))
        val syncRow = requireNotNull(dao.operation(stable(id, "sync").toString()))
        val sync = codecs.decodeSource6("PersonalSyncRequest", syncRow.payloadJson, WireSource6PersonalSyncRequest.serializer())
        val event = sync.events.single() as WireSource6PersonalLessonEvent
        requireOwned(scope, syncRow)
        require(syncRow.kind == "fresh_practice_sync" && event.payload.lessonId == intent.newLessonId && event.payload.contentRef == pinned &&
            event.eventId == stable(id, "event").toString() && sync.operationId == stable(id, "sync").toString())
        if (intent.state == "prepared") {
            val response: LearningReply<WireSource6PersonalSyncReceipt> = if (syncRow.status == "acknowledged") {
                LearningReply.Stored(scope, codecs.decodeSource6("PersonalSyncReceipt", requireNotNull(syncRow.receiptJson), WireSource6PersonalSyncReceipt.serializer()))
            } else {
                require(syncRow.status == "pending")
                api.call("source6", "/v2/profile/sync", "post", scope, codecs.parse("source6", syncRow.payloadJson), WireSource6PersonalSyncReceipt.serializer())
            }
            when (response) {
                is LearningReply.Failed -> return failure(scope, id, intent, response)
                is LearningReply.Stored -> {
                    if (response.scope != scope || !api.isCurrent(scope)) return FreshPracticeResult.Conflict(id, intent.newLessonId, "SESSION_CHANGED")
                    require(response.value.receipts.size == 1 && response.value.receipts.single().eventId == event.eventId)
                    dao.acknowledge(scope.owner, syncRow, codecs.encode("source6", "PersonalSyncReceipt", response.value, WireSource6PersonalSyncReceipt.serializer()))
                    review.acknowledgeFreshSync(scope, id.toString(), response)
                    intent = requireNotNull(dao.freshIntent(id.toString()))
                }
            }
        }
        // Existing sync snapshot verifies admission and caches this real lesson so the
        // existing resume repository can require an acknowledged reference.
        val snapshot = when (val read = listening.snapshot(scope)) {
            is LearningReply.Failed -> return failure(scope, id, intent, read)
            is LearningReply.Stored -> {
                if (read.scope != scope || !api.isCurrent(scope)) return FreshPracticeResult.Conflict(id, intent.newLessonId, "SESSION_CHANGED")
                read.value
            }
        }
        val lesson = snapshot.records.filterIsInstance<WireSource6PersonalReadLesson>().singleOrNull { it.recordId == intent.newLessonId }
            ?: return FreshPracticeResult.Pending(id, intent.newLessonId, "LESSON_NOT_ACKNOWLEDGED", true)
        require(lesson.payload.lessonId == intent.newLessonId && lesson.payload.contentRef == pinned && lesson.payload.lessonId != intent.sourceLessonId)
        if (intent.state == "selected") {
            if (snapshot.profile.activeResume?.kind != "practice" || snapshot.profile.activeResume?.recordId != intent.newLessonId)
                return FreshPracticeResult.Conflict(id, intent.newLessonId, "FRESH_SELECTION_SUPERSEDED")
            return FreshPracticeResult.Selected(lesson, discardUnsubmittedUi = false)
        }
        require(intent.state == "synced")
        val resumeRow = requireNotNull(dao.operation(stable(id, "resume").toString()))
        requireOwned(scope, resumeRow)
        val resume = codecs.decodeSource6("PersonalResumeSelection", resumeRow.payloadJson, WireSource6PersonalResumeSelection.serializer())
        require(resumeRow.kind == "source6_resume" && resume.operationId == stable(id, "resume").toString() && resume.expectedResumeVersion == intent.expectedResumeVersion &&
            resume.activeResume?.kind == "practice" && resume.activeResume?.recordId == intent.newLessonId)
        return when (val selected = listening.resume(scope, resume)) {
            is LearningReply.Failed -> failure(scope, id, intent, selected)
            is LearningReply.Stored -> {
                if (selected.scope != scope || !api.isCurrent(scope)) return FreshPracticeResult.Conflict(id, intent.newLessonId, "SESSION_CHANGED")
                review.acknowledgeFreshSelection(scope, id.toString(), selected)
                // UI-only effect is returned after both durable ACKs. No old record is deleted.
                FreshPracticeResult.Selected(lesson, discardUnsubmittedUi = true)
            }
        }
    }
    private suspend fun failure(scope: LearningCallScope, id: UUID, intent: LocalFreshPracticeIntent, error: LearningReply.Failed): FreshPracticeResult {
        val conflicts = setOf("VERSION_MISMATCH", "RECORD_CONFLICT", "IDEMPOTENCY_CONFLICT", "STALE_EPOCH", "STALE_RESPONSE", "FORBIDDEN", "SESSION_CHANGED", "IMMUTABLE_OPERATION_CONFLICT")
        if (error.code in conflicts || !error.retryable) {
            if (api.isCurrent(scope) && intent.state in setOf("prepared", "synced")) dao.transitionFresh(scope.owner, id.toString(), intent.state, "conflict")
            return FreshPracticeResult.Conflict(id, intent.newLessonId, error.code)
        }
        return FreshPracticeResult.Pending(id, intent.newLessonId, error.code, error.retryable)
    }
    private fun context(scope: LearningCallScope) = WireSource6CommonProfileContext(scope.owner.profileId.toString(), scope.owner.privacyEpoch)
    private fun stable(id: UUID, suffix: String) = UUID.nameUUIDFromBytes("fresh-practice:$id:$suffix".toByteArray(Charsets.UTF_8))
    private fun operation(scope: LearningCallScope, id: String, kind: String, target: String, bytes: String) =
        LocalReviewOperation(id, scope.owner.profileId.toString(), scope.owner.privacyEpoch, scope.owner.revision, kind, target, bytes, LearningJson.hash(bytes))
    private fun requireOwned(scope: LearningCallScope, row: LocalReviewOperation) = require(row.profileId == scope.owner.profileId.toString() &&
        row.privacyEpoch == scope.owner.privacyEpoch && row.ownerRevision == scope.owner.revision && LearningJson.hash(row.payloadJson) == row.payloadHash && row.status != "isolated")
    private suspend fun guarded(scope: LearningCallScope, id: UUID, block: suspend () -> FreshPracticeResult): FreshPracticeResult = try { block() }
        catch (error: CancellationException) { throw error }
        catch (_: IllegalArgumentException) { FreshPracticeResult.Conflict(id, knownLessonId(id), if (api.isCurrent(scope)) "INVALID_FRESH_INTENT" else "SESSION_CHANGED") }
        catch (_: IllegalStateException) { FreshPracticeResult.Conflict(id, knownLessonId(id), if (api.isCurrent(scope)) "INVALID_FRESH_INTENT" else "SESSION_CHANGED") }
        catch (_: Exception) {
            if (!api.isCurrent(scope)) FreshPracticeResult.Conflict(id, knownLessonId(id), "SESSION_CHANGED")
            else FreshPracticeResult.Pending(id, knownLessonId(id), "STORAGE_UNAVAILABLE", true)
        }
    private suspend fun knownLessonId(id: UUID): String? = try { dao.freshIntent(id.toString())?.newLessonId }
        catch (error: CancellationException) { throw error } catch (_: Exception) { null }
}
