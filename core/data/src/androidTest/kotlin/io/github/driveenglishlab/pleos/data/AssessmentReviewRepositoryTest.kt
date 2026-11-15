package io.github.driveenglishlab.pleos.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.driveenglishlab.pleos.data.wire.WireCommonPracticeContentRef
import io.github.driveenglishlab.pleos.domain.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real strict R7 codecs + Room CAS/journal; no inference/network or synthetic server ACK. */
@RunWith(AndroidJUnit4::class)
class AssessmentReviewRepositoryTest {
    private val owner = OwnerContext(UUID.randomUUID(), 0, 1, UUID.randomUUID())
    private val scope = LearningCallScope(owner, LearningSession(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "test-session"))
    private val lessonId = UUID.randomUUID()
    private lateinit var attemptId: UUID
    private lateinit var source: PracticeContentRef
    private lateinit var feedback: JsonObject
    private lateinit var db: LearningDatabase
    private lateinit var codecs: IncrementalContractJson
    private lateinit var repository: AssessmentReviewRepository
    private var live: LearningCallScope? = scope
    private var elapsed = 0L
    private var wall = 1_800_000_000_000L
    private var gets = 0
    private val posts = mutableListOf<JsonObject>()
    private var response: JsonObject? = null
    private var onGet: suspend () -> Unit = {}
    private var onPost: suspend (JsonObject) -> LearningHttpResponse = { response = kept(it); admission(it) }

    @Before fun setup() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, LearningDatabase::class.java).build()
        db.learning().putOwner(LocalOwner(profileId = owner.profileId.toString(), privacyEpoch = 0, revision = 1, status = "active"))
        codecs = IncrementalContractJson(context)
        val fixture = contractFixture("FeedbackResponse")
        attemptId = UUID.fromString(fixture.getValue("attempt_id").jsonPrimitive.content)
        source = learningJson.decodeFromJsonElement(WireCommonPracticeContentRef.serializer(), fixture.getValue("content_ref")).domainPractice()
        val keys = setOf("attempt_id", "content_ref", "judgement", "focus", "feedback_ko", "evidence_refs", "correction",
            "example_sentence", "support_issued", "retry", "generation", "focus_fact_refs", "retry_outcome", "prepared_supports", "english_attempt_status")
        feedback = JsonObject(fixture.filterKeys { it in keys })
        val transport = object : LearningHttpTransport {
            override suspend fun exchange(method: String, path: String, query: Map<String, String>, headers: Map<String, String>, body: ByteArray?): LearningHttpResponse {
                if (method.equals("get", true)) {
                    gets++
                    assertEquals(owner.profileId.toString(), query["profile_id"])
                    assertEquals("0", query["privacy_epoch"])
                    assertEquals(setOf("service_contract_version", "lookup", "profile_id", "privacy_epoch"), query.keys)
                    onGet()
                    return jsonResponse(response ?: projection())
                }
                val request = learningJson.parseToJsonElement(body!!.toString(Charsets.UTF_8)).jsonObject
                posts += request
                assertEquals(request.getValue("operation_id").jsonPrimitive.content, headers["Idempotency-Key"])
                assertFalse(repository.state.value.entries.getValue(attemptId).displayReady)
                return onPost(request)
            }
        }
        repository = AssessmentReviewRepository(IncrementalLearningApi(LearningSessionSource { live }, codecs, transport), codecs, db, { elapsed }, { wall })
    }
    @After fun close() { db.close() }

    private fun projection(revision: Long = 1, assessmentRevision: Long = 1, hash: String = "a".repeat(64),
        status: String = "valid", reviewStatus: String = "none", review: JsonElement = JsonNull) = buildJsonObject {
        put("service_contract_version", 1)
        putJsonObject("profile_context") { put("profile_id", owner.profileId.toString()); put("privacy_epoch", owner.privacyEpoch) }
        put("review", review)
        putJsonObject("effective_assessment") {
            put("lesson_id", lessonId.toString()); put("attempt_id", attemptId.toString()); put("assessment_revision", assessmentRevision)
            put("basis_assessment_hash", hash); put("original_feedback_hash", "a".repeat(64)); put("projection_revision", revision)
            put("evaluation_status", status); put("review_status", reviewStatus); put("reason_code", JsonNull); put("correction_event_id", JsonNull)
            put("effective_feedback_hash", if (status == "valid") JsonPrimitive(hash) else JsonNull)
            put("feedback_format", if (status == "valid") JsonPrimitive("legacy5") else JsonNull)
            put("feedback", if (status == "valid") feedback else JsonNull)
        }
    }
    private fun admission(request: JsonObject) = jsonResponse(buildJsonObject {
        put("service_contract_version", 1); put("operation_id", request.getValue("operation_id")); put("profile_context", request.getValue("profile_context"))
        putJsonObject("receipt") {
            put("review_id", request.getValue("review_id")); put("command", request.getValue("command")); put("accepted_version", 1)
            put("accepted_at", "2026-11-15T01:00:00Z"); put("disposition", "created")
        }
    }, 202)
    private fun kept(request: JsonObject) = projection(2, reviewStatus = "kept", review = buildJsonObject {
        for (key in listOf("review_id", "lesson_id", "attempt_id", "source_ref", "target_assessment_revision", "target_assessment_hash")) put(key, request.getValue(key))
        put("basis_hash", "c".repeat(64)); put("version", 2); put("state", "kept")
        put("requested_at", "2026-11-15T01:00:00Z"); put("updated_at", "2026-11-15T01:00:01Z")
        put("completed_at", "2026-11-15T01:00:01Z"); put("outcome", "kept")
        put("reason_code", "confirmed"); put("reason_ko", "원래 판정을 확인했어요.")
        put("correction_event_id", JsonNull); put("retry_after", JsonNull); put("can_retry", false); put("effective_assessment_revision", 1)
    })
    private fun failure() = jsonResponse(buildJsonObject {
        put("service_contract_version", 1)
        putJsonObject("error") { put("code", "UPSTREAM_UNAVAILABLE"); put("retryable", true); put("current_version", JsonNull) }
    }, 503)
    private fun row(value: JsonObject) = LocalAiLearningCache(owner.profileId.toString(), 0, "assessment_review", attemptId.toString(),
        value.getValue("effective_assessment").jsonObject.getValue("projection_revision").jsonPrimitive.long, incrementalCanonical(value))

    @Test fun passiveReadUsesRequiredOwnerQueryAndNeverStartsReview() = runBlocking {
        assertTrue(repository.read(scope, lessonId, attemptId) is LearningReply.Stored)
        assertTrue(repository.state.value.entries.getValue(attemptId).displayReady)
        assertEquals(1, gets); assertTrue(posts.isEmpty())
    }
    @Test fun lostAdmissionKeepsOriginalBytesAndSameBasisDoesNotAllocateNewOperation() = runBlocking {
        onPost = { failure() }
        repository.request(scope, lessonId, attemptId, source, "record")
        val original = db.aiLearning().assessmentRequests(owner.profileId.toString(), 0).single()
        assertFalse(repository.state.value.entries.getValue(attemptId).displayReady)
        onPost = { response = kept(it); admission(it) }
        repository.request(scope, lessonId, attemptId, source, "record")
        assertEquals(posts[0], posts[1]); assertEquals(original.payloadJson, db.aiLearning().operation(original.operationId)!!.payloadJson)
        assertEquals("acknowledged", db.aiLearning().operation(original.operationId)!!.state)
        repository.request(scope, lessonId, attemptId, source, "record")
        assertEquals(2, posts.size)
        assertNotNull(db.aiLearning().cache(owner.profileId.toString(), 0, "assessment_review_receipt", original.operationId))
    }
    @Test fun admissionReceiptNeverMakesAnUnrelatedOldProjectionDisplayable() = runBlocking {
        onPost = { admission(it) } // stale GET remains valid/none despite the 202
        val result = repository.request(scope, lessonId, attemptId, source, "record")
        assertEquals("INVALID_ASSESSMENT_PROJECTION", (result as LearningReply.Failed).code)
        assertFalse(repository.state.value.entries.getValue(attemptId).displayReady)
        val row = db.aiLearning().assessmentRequests(owner.profileId.toString(), 0).single()
        assertEquals("acknowledged", row.state)
        assertNotNull(db.aiLearning().cache(owner.profileId.toString(), 0, "assessment_review_receipt", row.operationId))
    }
    @Test fun existingBasisFromAnotherDeviceUsesTheAuthoritativeReviewIdentity() = runBlocking {
        val existingReview = UUID.randomUUID().toString()
        onPost = { request ->
            val joined = request.changed("review_id" to JsonPrimitive(existingReview))
            response = kept(joined)
            val envelope = learningJson.parseToJsonElement(admission(joined).body.toString(Charsets.UTF_8)).jsonObject
            jsonResponse(envelope.changed("receipt" to envelope.getValue("receipt").jsonObject.changed("disposition" to JsonPrimitive("existing"))), 202)
        }
        assertTrue(repository.request(scope, lessonId, attemptId, source, "record") is LearningReply.Stored)
        assertEquals(UUID.fromString(existingReview), repository.state.value.entries.getValue(attemptId).reviewId)
        assertTrue(repository.state.value.entries.getValue(attemptId).displayReady)
    }
    @Test fun expiredLostAdmissionCannotRenewDeadlineOrPostWithNewIdentity() = runBlocking {
        onPost = { failure() }
        repository.request(scope, lessonId, attemptId, source, "gleo")
        val original = db.aiLearning().assessmentRequests(owner.profileId.toString(), 0).single()
        wall += 6_001
        val reply = repository.request(scope, lessonId, attemptId, source, "record")
        assertEquals("DEADLINE_EXCEEDED", (reply as LearningReply.Failed).code)
        assertEquals(1, posts.size); assertEquals(original.payloadJson, db.aiLearning().operation(original.operationId)!!.payloadJson)
        assertFalse(repository.state.value.entries.getValue(attemptId).loading)
    }
    @Test fun initialCurrentReadConsumesTheGleoTotalBudget() = runBlocking {
        onGet = { elapsed = 6_001 }
        val reply = repository.request(scope, lessonId, attemptId, source, "gleo")
        assertEquals("DEADLINE_EXCEEDED", (reply as LearningReply.Failed).code)
        assertTrue(posts.isEmpty()); assertFalse(repository.state.value.entries.getValue(attemptId).displayReady)
    }
    @Test fun cancellationClosesLoadingAndAllowsExplicitReadWithoutOldFallback() = runBlocking {
        repository.read(scope, lessonId, attemptId)
        val entered = CompletableDeferred<Unit>()
        onGet = { entered.complete(Unit); awaitCancellation() }
        val pending = launch { repository.request(scope, lessonId, attemptId, source, "record") }
        entered.await(); pending.cancelAndJoin()
        val state = repository.state.value.entries.getValue(attemptId)
        assertFalse(state.loading); assertFalse(state.displayReady); assertTrue(state.failed)
        onGet = {}; repository.read(scope, lessonId, attemptId)
        assertTrue(repository.state.value.entries.getValue(attemptId).displayReady)
    }
    @Test fun clearedEntryAndChangedSessionRejectLateProjection() = runBlocking {
        onGet = { repository.clear() }
        assertTrue(repository.read(scope, lessonId, attemptId) is LearningReply.Failed)
        assertTrue(repository.state.value.entries.isEmpty())
        onGet = { live = null }
        assertTrue(repository.read(scope, lessonId, attemptId) is LearningReply.Failed)
        assertNull(db.aiLearning().cache(owner.profileId.toString(), 0, "assessment_review", attemptId.toString()))
    }
    @Test fun withheldCorrectedRevisionNeverFallsBackToOriginalSuccess() = runBlocking {
        repository.read(scope, lessonId, attemptId)
        response = projection(2, 2, "b".repeat(64), "withheld", "failed")
        assertTrue(repository.read(scope, lessonId, attemptId) is LearningReply.Stored)
        val state = repository.state.value.entries.getValue(attemptId)
        assertEquals("withheld", state.evaluationStatus); assertNull(state.feedbackKo); assertNull(state.contentSuccess)
        assertEquals(JsonPrimitive("b".repeat(64)), state.effective!!["basis_assessment_hash"])
    }
    @Test fun projectionCasRejectsSameVersionRewriteAndPreservesOriginalLineage() = runBlocking {
        val dao = db.aiLearning(); val original = projection()
        assertTrue(dao.saveAssessmentProjection(owner, row(original)))
        assertFalse(dao.saveAssessmentProjection(owner, row(original)))
        val changed = projection(status = "withheld")
        var rejected = false
        try { dao.saveAssessmentProjection(owner, row(changed)) } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        val next = projection(2, 2, "b".repeat(64), "withheld", "failed")
        assertTrue(dao.saveAssessmentProjection(owner, row(next)))
        assertFalse(dao.saveAssessmentProjection(owner, row(original)))
        assertEquals(row(next), dao.cache(owner.profileId.toString(), 0, "assessment_review", attemptId.toString()))
    }
    @Test fun reviewVersionCannotChangeItsImmutableEvidenceBasis() = runBlocking {
        val request = buildJsonObject {
            put("review_id", UUID.randomUUID().toString()); put("lesson_id", lessonId.toString()); put("attempt_id", attemptId.toString())
            put("source_ref", buildJsonObject { put("source_kind", "legacy_bundle"); put("legacy_ref", feedback.getValue("content_ref")) })
            put("target_assessment_revision", 1); put("target_assessment_hash", "a".repeat(64))
        }
        val original = kept(request)
        assertTrue(db.aiLearning().saveAssessmentProjection(owner, row(original)))
        val changedReview = original.getValue("review").jsonObject.changed("version" to JsonPrimitive(3), "basis_hash" to JsonPrimitive("d".repeat(64)))
        val next = original.changed("review" to changedReview,
            "effective_assessment" to original.getValue("effective_assessment").jsonObject.changed("projection_revision" to JsonPrimitive(3)))
        var rejected = false
        try { db.aiLearning().saveAssessmentProjection(owner, row(next)) } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        assertEquals(row(original), db.aiLearning().cache(owner.profileId.toString(), 0, "assessment_review", attemptId.toString()))
    }
    @Test fun confirmedEpochDeletionCannotBeRepopulatedByOldOwnerResponse() = runBlocking {
        repository.read(scope, lessonId, attemptId)
        db.aiLearning().deleteEpochCache(owner.profileId.toString(), 0)
        db.aiLearning().deleteEpochJournal(owner.profileId.toString(), 0)
        db.learning().putOwner(LocalOwner(profileId = owner.profileId.toString(), privacyEpoch = 1, revision = 2, status = "active"))
        assertTrue(repository.read(scope, lessonId, attemptId) is LearningReply.Failed)
        assertNull(db.aiLearning().cache(owner.profileId.toString(), 0, "assessment_review", attemptId.toString()))
        assertFalse(repository.state.value.entries.getValue(attemptId).displayReady)
    }
}
