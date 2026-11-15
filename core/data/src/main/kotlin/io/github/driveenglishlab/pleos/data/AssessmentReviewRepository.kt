package io.github.driveenglishlab.pleos.data

import android.os.SystemClock
import androidx.room.withTransaction
import io.github.driveenglishlab.pleos.domain.*
import io.github.driveenglishlab.pleos.data.wire.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Current assessment is separate from the immutable answer and its original feedback. */
data class AssessmentEntryState(val owner: OwnerContext, val lessonId: UUID, val attemptId: UUID,
    val effective: JsonObject? = null, val review: JsonObject? = null,
    val loading: Boolean = false, val pending: Boolean = false, val failed: Boolean = false,
    val errorCode: String? = null) {
    val evaluationStatus get() = effective?.get("evaluation_status")?.jsonPrimitive?.content
    val reviewStatus get() = effective?.get("review_status")?.jsonPrimitive?.content
    val displayReady get() = !loading && !pending && !failed && evaluationStatus == "valid" && effective?.get("feedback") is JsonObject
    val feedbackKo get() = if (displayReady) effective?.get("feedback")?.jsonObject?.get("feedback_ko")?.jsonPrimitive?.content else null
    val judgement get() = if (displayReady) effective?.get("feedback")?.jsonObject?.get("judgement")?.jsonPrimitive?.content else null
    val contentSuccess get() = if (displayReady) judgement == "sufficient" else null
    val canRetry get() = !loading && !pending && review?.get("can_retry")?.jsonPrimitive?.boolean == true
    val canRequest get() = !loading && !pending && !failed && effective != null && reviewStatus !in setOf("in_review")
    val reviewId get() = review?.get("review_id")?.jsonPrimitive?.content?.let(UUID::fromString)
    val reasonKo get() = review?.get("reason_ko")?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
}
data class AssessmentReviewState(val owner: OwnerContext? = null, val entries: Map<UUID, AssessmentEntryState> = emptyMap())

/** Uses the same owner/epoch journal and deletion transaction as the existing learning data. */
class AssessmentReviewRepository(private val api: IncrementalLearningApi, private val codecs: IncrementalContractJson,
    private val database: LearningDatabase, private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
    private val wallTimeMs: () -> Long = System::currentTimeMillis) {
    private val lane = Mutex()
    private val mutable = MutableStateFlow(AssessmentReviewState())
    val state = mutable.asStateFlow()
    private val dao get() = database.aiLearning()

    private var entryGeneration = 0L
    fun clear() { entryGeneration++; mutable.value = AssessmentReviewState() }
    private fun put(value: AssessmentEntryState) {
        val before = mutable.value
        mutable.value = if (before.owner == value.owner) before.copy(entries = before.entries + (value.attemptId to value))
            else AssessmentReviewState(value.owner, mapOf(value.attemptId to value))
    }
    suspend fun bind(scope: LearningCallScope) {
        if (api.isCurrent(scope) && mutable.value.owner != scope.owner) { entryGeneration++; mutable.value = AssessmentReviewState(scope.owner) }
    }

    /** GET never starts evaluation, feedback presentation, a lesson or playback. */
    suspend fun read(scope: LearningCallScope, lessonId: UUID, attemptId: UUID): LearningReply<AssessmentEntryState> = lane.withLock {
        readLocked(scope, lessonId, attemptId)
    }
    private suspend fun readLocked(scope: LearningCallScope, lessonId: UUID, attemptId: UUID, generation: Long = entryGeneration, holdDisplay: Boolean = false): LearningReply<AssessmentEntryState> {
        if (generation != entryGeneration || !api.isCurrent(scope)) return LearningReply.Failed("SESSION_CHANGED", false)
        val old = mutable.value.entries[attemptId]?.takeIf { it.owner == scope.owner && it.lessonId == lessonId }
            ?: AssessmentEntryState(scope.owner, lessonId, attemptId)
        put(old.copy(loading = true, failed = false, errorCode = null))
        return try { when (val reply = api.call("assessment-review", "/v2/assessment-reviews/{target_id}", "get", scope, null,
            JsonObject.serializer(), mapOf("target_id" to attemptId.toString()), mapOf("service_contract_version" to "1", "lookup" to "attempt",
                "profile_id" to scope.owner.profileId.toString(), "privacy_epoch" to scope.owner.privacyEpoch.toString()))) {
            is LearningReply.Failed -> {
                if (generation == entryGeneration && api.isCurrent(scope)) put(old.copy(loading = false, failed = true, errorCode = reply.code))
                reply
            }
            is LearningReply.Stored -> try {
                val value = accept(scope, lessonId, attemptId, reply.value, generation, holdDisplay)
                LearningReply.Stored(scope, value)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                if (generation == entryGeneration && api.isCurrent(scope)) put(old.copy(loading = false, failed = true, errorCode = "INVALID_ASSESSMENT_PROJECTION"))
                LearningReply.Failed("INVALID_ASSESSMENT_PROJECTION", false)
            }
        } } catch (error: CancellationException) {
            withContext(NonCancellable) {
                if (generation == entryGeneration && api.isCurrent(scope))
                    put(old.copy(loading = false, pending = false, failed = true, errorCode = "QUERY_CANCELLED"))
            }
            throw error
        }
    }
    private suspend fun accept(scope: LearningCallScope, lessonId: UUID, attemptId: UUID, response: JsonObject, generation: Long, holdDisplay: Boolean): AssessmentEntryState {
        require(generation == entryGeneration && api.isCurrent(scope))
        codecs.validateAssessmentLookup(buildJsonObject {
            put("service_contract_version", 1); put("lookup", "attempt")
            put("profile_id", scope.owner.profileId.toString()); put("privacy_epoch", scope.owner.privacyEpoch)
        }, response, attemptId.toString())
        val effective = response.getValue("effective_assessment").jsonObject
        require(effective.getValue("lesson_id").jsonPrimitive.content == lessonId.toString() &&
            effective.getValue("attempt_id").jsonPrimitive.content == attemptId.toString())
        val status = effective.getValue("evaluation_status").jsonPrimitive.content
        if (status != "valid") for (key in listOf("effective_feedback_hash", "feedback_format", "feedback")) require(effective[key] == JsonNull)
        else require(effective["feedback"] is JsonObject && effective["effective_feedback_hash"] == effective["basis_assessment_hash"])
        val review = response.getValue("review").takeUnless { it == JsonNull }?.jsonObject
        review?.let { require(it.getValue("lesson_id").jsonPrimitive.content == lessonId.toString() && it.getValue("attempt_id").jsonPrimitive.content == attemptId.toString()) }
        // A current read cannot silently undo a known admission for this unchanged basis.
        val admitted = dao.assessmentRequests(scope.owner.profileId.toString(), scope.owner.privacyEpoch).lastOrNull { row ->
            if (row.state != "acknowledged") false else {
                val original = codecs.parse("assessment-review", row.payloadJson).jsonObject
                original["attempt_id"] == effective["attempt_id"] && original["target_assessment_revision"] == effective["assessment_revision"] &&
                    original["target_assessment_hash"] == effective["basis_assessment_hash"]
            }
        }
        if (admitted != null) {
            val receipt = requireNotNull(dao.cache(scope.owner.profileId.toString(), scope.owner.privacyEpoch,
                "assessment_review_receipt", admitted.operationId))
            val barrier = codecs.parse("assessment-review", receipt.payloadJson).jsonObject.getValue("receipt").jsonObject
            require(review != null && review["review_id"] == barrier["review_id"] &&
                review.getValue("version").jsonPrimitive.long >= barrier.getValue("accepted_version").jsonPrimitive.long)
        }
        val bytes = incrementalCanonical(response)
        val saved = dao.saveAssessmentProjection(scope.owner, LocalAiLearningCache(scope.owner.profileId.toString(), scope.owner.privacyEpoch,
            "assessment_review", attemptId.toString(), effective.getValue("projection_revision").jsonPrimitive.long, bytes))
        require(saved || dao.cache(scope.owner.profileId.toString(), scope.owner.privacyEpoch, "assessment_review", attemptId.toString())?.payloadJson == bytes)
        require(generation == entryGeneration && api.isCurrent(scope))
        val value = AssessmentEntryState(scope.owner, lessonId, attemptId, effective, review,
            pending = review?.get("state")?.jsonPrimitive?.content in setOf("queued", "running", "pending"))
        put(if (holdDisplay) value.copy(loading = true) else value)
        return value
    }

    /** One explicit action has one total budget, including its initial current-basis GET.
     * Lost admissions retain their original bytes and deadline; polling cannot renew them.
     */
    suspend fun request(scope: LearningCallScope, lessonId: UUID, attemptId: UUID, source: PracticeRef,
        entry: String, retry: Boolean = false): LearningReply<AssessmentEntryState> = lane.withLock {
        require(entry in setOf("parked_feedback", "record", "gleo"))
        val generation = entryGeneration
        val started = elapsedMs()
        val budget = if (entry == "gleo") 6_000L else 10_000L
        var activeOperation: LocalAiLearningJournal? = null
        var deadline = wallTimeMs() + budget
        var admissionBarrier: JsonObject? = null
        suspend fun current() = generation == entryGeneration && api.isCurrent(scope)
        fun remaining() = minOf(budget - (elapsedMs() - started), deadline - wallTimeMs())
        suspend fun fail(code: String, retryable: Boolean): LearningReply.Failed {
            if (current()) {
                val old = mutable.value.entries[attemptId] ?: AssessmentEntryState(scope.owner, lessonId, attemptId)
                put(old.copy(loading = false, pending = false, failed = true, errorCode = code))
            }
            return LearningReply.Failed(code, retryable)
        }
        suspend fun readWithinBudget(): LearningReply<AssessmentEntryState> {
            val left = remaining()
            val result = if (left > 0) withTimeoutOrNull(left) { readLocked(scope, lessonId, attemptId, generation, holdDisplay = true) }
                ?: fail("DEADLINE_EXCEEDED", true) else fail("DEADLINE_EXCEEDED", true)
            val barrier = admissionBarrier
            if (result is LearningReply.Stored && barrier != null) {
                val review = result.value.review
                if (review == null || review["review_id"] != barrier["review_id"] ||
                    review.getValue("version").jsonPrimitive.long < barrier.getValue("accepted_version").jsonPrimitive.long)
                    return fail("ASSESSMENT_REVIEW_NOT_CURRENT", true)
                if (current()) put(result.value)
            }
            return result
        }
        try {
            if (!current()) return@withLock LearningReply.Failed("SESSION_CHANGED", false)
            val requests = dao.assessmentRequests(scope.owner.profileId.toString(), scope.owner.privacyEpoch).filter {
                codecs.parse("assessment-review", it.payloadJson).jsonObject["attempt_id"] == JsonPrimitive(attemptId.toString())
            }
            val unsettled = requests.firstOrNull { it.state in setOf("pending", "sending", "conflict") }
            if (unsettled != null) {
                activeOperation = unsettled
                // Missing old metadata is unknown, never permission to invent a new deadline.
                val stored = dao.cache(scope.owner.profileId.toString(), scope.owner.privacyEpoch,
                    "assessment_review_deadline", unsettled.operationId)
                deadline = stored?.payloadJson?.let { codecs.parse("assessment-review", it).jsonObject["deadline_ms"]?.jsonPrimitive?.long } ?: 0L
            }
            val fresh = readWithinBudget()
            if (fresh is LearningReply.Failed) return@withLock fresh
            val currentValue = (fresh as LearningReply.Stored).value
            val effective = requireNotNull(currentValue.effective)
            val sourceJson = codecs.parse("source6", codecs.encode("source6", "PracticeContentRef6", source.source6Wire(), WireSource6PracticeContentRef6.serializer()))
            val operation = unsettled ?: run {
                if (retry && !currentValue.canRetry || !retry && !currentValue.canRequest) {
                    if (current()) put(currentValue)
                    return@withLock LearningReply.Failed("ASSESSMENT_REVIEW_NOT_ACTIONABLE", false)
                }
                // A completed admission for this exact basis is not a new inference request.
                val admittedBasis = requests.lastOrNull {
                    val previous = codecs.parse("assessment-review", it.payloadJson).jsonObject
                    it.state == "acknowledged" && previous["target_assessment_revision"] == effective["assessment_revision"] &&
                        previous["target_assessment_hash"] == effective["basis_assessment_hash"] &&
                        (!retry || previous["command"] == JsonPrimitive("retry") && previous["expected_review_version"] == currentValue.review?.get("version"))
                }
                if (admittedBasis != null) {
                    val savedReceipt = dao.cache(scope.owner.profileId.toString(), scope.owner.privacyEpoch,
                        "assessment_review_receipt", admittedBasis.operationId)?.payloadJson
                        ?.let { codecs.parse("assessment-review", it).jsonObject.getValue("receipt").jsonObject }
                    val review = currentValue.review
                    if (savedReceipt == null || review == null || review["review_id"] != savedReceipt["review_id"] ||
                        review.getValue("version").jsonPrimitive.long < savedReceipt.getValue("accepted_version").jsonPrimitive.long)
                        return@withLock fail("ASSESSMENT_REVIEW_NOT_CURRENT", true)
                    if (current()) put(currentValue)
                    return@withLock fresh
                }
                val request = buildJsonObject {
                    put("service_contract_version", 1); put("operation_id", UUID.randomUUID().toString())
                    put("profile_context", buildJsonObject { put("profile_id", scope.owner.profileId.toString()); put("privacy_epoch", scope.owner.privacyEpoch) })
                    put("command", if (retry) "retry" else "request")
                    put("review_id", if (retry) requireNotNull(currentValue.reviewId).toString() else UUID.randomUUID().toString())
                    put("lesson_id", lessonId.toString()); put("attempt_id", attemptId.toString()); put("source_ref", sourceJson)
                    put("target_assessment_revision", effective.getValue("assessment_revision")); put("target_assessment_hash", effective.getValue("basis_assessment_hash"))
                    put("expected_review_version", if (retry) currentValue.review!!.getValue("version") else JsonPrimitive(0))
                    put("client_context", learningJson.encodeToJsonElement(WireSource6CommonClientContext.serializer(), scope.owner.source6Context()))
                    put("entry", entry)
                }
                codecs.validateDefinition("assessment-review", "AssessmentReviewRequest", request)
                val bytes = incrementalCanonical(request)
                LocalAiLearningJournal(request.getValue("operation_id").jsonPrimitive.content, scope.owner.profileId.toString(), scope.owner.privacyEpoch,
                    scope.owner.revision, "assessment_review_request", bytes, LearningJson.hash(bytes), "pending", false).also { row ->
                    database.withTransaction {
                        dao.queue(scope.owner, row)
                        dao.saveCache(scope.owner, LocalAiLearningCache(scope.owner.profileId.toString(), scope.owner.privacyEpoch,
                            "assessment_review_deadline", row.operationId, 1, buildJsonObject { put("deadline_ms", deadline) }.toString()))
                    }
                }
            }
            activeOperation = operation
            require(operation.ownerRevision == scope.owner.revision && operation.payloadHash == LearningJson.hash(operation.payloadJson))
            val request = codecs.parse("assessment-review", operation.payloadJson).jsonObject
            require(request["lesson_id"] == JsonPrimitive(lessonId.toString()) && request["source_ref"] == sourceJson)
            if (operation.state == "conflict") return@withLock fail("ASSESSMENT_REVIEW_CONFLICT", false)
            if (operation.state == "pending") require(dao.transition(scope.owner, operation.operationId, "pending", "sending", false))
            if (!current()) return@withLock LearningReply.Failed("SESSION_CHANGED", false)
            put(currentValue.copy(loading = false, pending = true, failed = false, errorCode = null))
            val left = remaining()
            val admitted = if (left > 0) withTimeoutOrNull(left) {
                api.call("assessment-review", "/v2/assessment-reviews", "post", scope, request, JsonObject.serializer())
            } else null
            when (admitted) {
                null -> {
                    if (current()) dao.transition(scope.owner, operation.operationId, "sending", "pending", false)
                    return@withLock fail("DEADLINE_EXCEEDED", true)
                }
                is LearningReply.Failed -> {
                    if (current()) dao.transition(scope.owner, operation.operationId, "sending", if (admitted.retryable) "pending" else "conflict", false)
                    return@withLock fail(admitted.code, admitted.retryable)
                }
                is LearningReply.Stored -> {
                    require(current())
                    val receipt = admitted.value
                    codecs.validateDefinition("assessment-review", "AssessmentReviewAdmissionResponse", receipt)
                    require(receipt["operation_id"] == request["operation_id"])
                    val admission = receipt.getValue("receipt").jsonObject
                    require(admission["command"] == request["command"])
                    // Another device may have admitted this same immutable basis first.
                    // Only a request/existing receipt may join its server-assigned review ID.
                    require(admission["review_id"] == request["review_id"] ||
                        request["command"] == JsonPrimitive("request") && admission["disposition"] == JsonPrimitive("existing"))
                    require(admission.getValue("accepted_version").jsonPrimitive.long > request.getValue("expected_review_version").jsonPrimitive.long)
                    admissionBarrier = admission
                    database.withTransaction {
                        dao.saveCache(scope.owner, LocalAiLearningCache(scope.owner.profileId.toString(), scope.owner.privacyEpoch,
                            "assessment_review_receipt", operation.operationId, 1, incrementalCanonical(receipt)))
                        require(dao.transition(scope.owner, operation.operationId, "sending", "acknowledged", false))
                    }
                }
            }
            var result = readWithinBudget()
            repeat(3) {
                if ((result as? LearningReply.Stored)?.value?.pending == true && remaining() > 750 && current()) {
                    delay(750); result = readWithinBudget()
                }
            }
            result
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                if (current()) {
                    activeOperation?.let { runCatching { dao.transition(scope.owner, it.operationId, "sending", "pending", false) } }
                    fail("REQUEST_CANCELLED", true)
                }
            }
            throw error
        } catch (_: Exception) {
            if (current()) activeOperation?.let { runCatching { dao.transition(scope.owner, it.operationId, "sending", "pending", false) } }
            fail("INVALID_ASSESSMENT_PROJECTION", false)
        }
    }
}
