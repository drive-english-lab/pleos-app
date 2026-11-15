package io.github.driveenglishlab.pleos.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PracticeRepositoryTest {
    private lateinit var db: LearningDatabase
    private val owner = OwnerContext(UUID.randomUUID(), 0, 1, UUID.randomUUID())
    private val scope = LearningCallScope(owner, LearningSession(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "test-user-session"))
    private lateinit var task: PracticeContentRef
    private val lessonId = UUID.randomUUID()
    private var current = true
    private var elapsed = 0L
    private lateinit var transport: Transport
    private lateinit var repository: PracticeRepository

    @Before fun setup() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, LearningDatabase::class.java).build()
        db.learning().putOwner(LocalOwner(profileId = owner.profileId.toString(), privacyEpoch = 0, revision = 1, status = "active"))
        val wire = learningJson.decodeFromJsonElement(WireCommonPracticeContentRef.serializer(), contractFixture("FeedbackResponse").getValue("content_ref"))
        task = PracticeContentRef(ContentRef(UUID.fromString(wire.articleId), UUID.fromString(wire.bundleId), UUID.fromString(wire.sourceSnapshotId),
            UUID.fromString(wire.listeningScriptId), ListeningMode.ORIGINAL_FULL), UUID.fromString(wire.taskId), UUID.fromString(wire.basicQuestionVersionId))
        transport = Transport()
        val contracts = LearningContractJson(context)
        val api = LearningApi(LearningSessionSource { scope }, contracts, transport)
        repository = PracticeRepository(api, db.learning(), LearningStore(db), contracts,
            { contextOwner, id, ref -> current && contextOwner == owner && id == lessonId && ref == task }, elapsedMs = { elapsed })
        repository.open(scope, LessonInput(lessonId, task, LessonInputPhase.QUESTION_WAIT, HelpSnapshot(), null, DialogueProgress(false)), "What changed?")
        Unit
    }
    @After fun close() { db.close() }

    private suspend fun accepted(): AcceptedAnswer {
        val access = AccessState(SafetyState(VehiclePosition.PARKED, true, true, true), WindowState(true, true), PowerAvailability.ALLOWED,
            microphoneGranted = true, directProviderVerified = true, inputArbitrationVerified = true, audioAllowsReply = true, ownerValid = true,
            // Synthetic direct-capture evidence for this repository fixture, not runtime approval.
            components = ComponentPolicies(generation = 1, microphone = ComponentAccess(known = true, allowed = true, sourceGeneration = 1, permissionGeneration = 1)),
            directReplyFocusAllowed = true)
        val lease = InputLease(); val token = lease.start(owner, task, lessonId, access)!!; lease.finish(token.captureId)
        val answer = lease.finalText(token.captureId, "The company expanded production.", access, owner, task,
            Capture(CapturePath.PARKED_STT, CaptureFidelity.UNKNOWN, emptyList(), SpokenLanguage.EN, EnglishEvidence.VERIFIED_PATH, "test-only-route"))!!
        repository.persistAccepted(answer)
        return answer
    }

    private inner class Transport : LearningHttpTransport {
        val calls = mutableListOf<Pair<String, String>>()
        val feedbackBodies = mutableListOf<String>()
        var failFeedback = false
        var wrongReceipt = false
        var afterFeedback: suspend () -> Unit = {}
        var result: JsonObject? = null
        override suspend fun exchange(method: String, path: String, query: Map<String,String>, headers: Map<String,String>, body: ByteArray?): LearningHttpResponse {
            calls += method.uppercase(java.util.Locale.ROOT) to path
            val request = body?.let { learningJson.parseToJsonElement(it.toString(Charsets.UTF_8)).jsonObject }
            if (path == "/v1/profile/sync") {
                val receipts = request!!.getValue("events").jsonArray.map { event ->
                    val value = event.jsonObject; val payload = value.getValue("payload").jsonObject
                    buildJsonObject {
                        put("event_id", value.getValue("event_id")); put("record_id", if (wrongReceipt) JsonPrimitive(UUID.randomUUID().toString())
                            else payload.getValue(if (value.getValue("kind").jsonPrimitive.content == "lesson") "lesson_id" else "attempt_id"))
                        put("version", 1); put("outcome", "created")
                    }
                }
                return jsonResponse(buildJsonObject { put("schema_version","5"); put("profile_context",request.getValue("profile_context")); put("receipts",JsonArray(receipts)); put("cursor","fixture-page") })
            }
            if (path == "/v1/attempt-feedback") {
                feedbackBodies += body!!.toString(Charsets.UTF_8)
                if (failFeedback) return jsonResponse(buildJsonObject {
                    put("schema_version","5"); put("operation_id",request!!.getValue("operation_id"))
                    putJsonObject("error") { put("code","UPSTREAM_UNAVAILABLE"); put("message_ko","저장한 답변으로 다시 시도할 수 있어요."); put("retryable",true) }
                },503)
                val template = contractFixture("FeedbackResponse")
                val state = template.getValue("lesson_state").jsonObject.changed("lesson_id" to JsonPrimitive(lessonId.toString()),
                    "valid_first_attempt_id" to request!!.getValue("attempt_id"), "pending_feedback_attempt_id" to request.getValue("attempt_id"))
                val response = template.changed("operation_id" to request.getValue("operation_id"), "attempt_id" to request.getValue("attempt_id"),
                    "content_ref" to request.getValue("content_ref"), "client_context" to request.getValue("client_context"), "lesson_state" to state,
                    "example_exposure" to template.getValue("example_exposure").jsonObject.changed("boundary_id" to request.getValue("attempt_id")))
                val stored = JsonObject(response.filterKeys { it !in setOf("schema_version","operation_id","client_context","lesson_state","example_exposure") })
                result = buildJsonObject {
                    put("schema_version","5"); put("profile_context",learningJson.encodeToJsonElement(WireCommonProfileContext.serializer(),owner.profileWire()))
                    put("attempt_id",request.getValue("attempt_id")); put("content_ref",request.getValue("content_ref")); put("status","succeeded")
                    put("feedback",stored); put("lesson_state",state); put("example_exposure",response.getValue("example_exposure")); put("english_attempt_status","verified_english")
                }
                afterFeedback()
                return jsonResponse(response)
            }
            if (method.equals("GET", true) && path.startsWith("/v1/attempts/")) return jsonResponse(requireNotNull(result))
            error("Unexpected repository request.")
        }
    }

    @Test fun failureKeepsAcceptedInputAndExplicitRetryUsesTheSameImmutableRequest() = runBlocking {
        val answer = accepted(); transport.failFeedback = true
        val first = repository.submit(scope,answer.lease.attemptId,InteractionChannel.PARKED)
        assertTrue(first is PracticeResult.Failed)
        assertNotNull(db.learning().record(owner.profileId.toString(),0,answer.lease.attemptId.toString()))
        assertTrue(db.learning().pendingForRecords(owner.profileId.toString(),0,listOf(answer.lease.attemptId.toString())).isEmpty())
        transport.failFeedback = false
        val retry = repository.submit(scope,answer.lease.attemptId,InteractionChannel.PARKED)
        assertTrue(retry is PracticeResult.Saved)
        assertEquals(transport.feedbackBodies.first(),transport.feedbackBodies.last())
        assertEquals(2,transport.feedbackBodies.size)
        assertEquals(1,transport.calls.count { it.second == "/v1/profile/sync" })
    }

    @Test fun wrongRecordReceiptDoesNotRemoveAnyPendingInputOrStartEvaluation() = runBlocking {
        val answer = accepted(); transport.wrongReceipt = true
        val result = repository.submit(scope,answer.lease.attemptId,InteractionChannel.PARKED)
        assertEquals("INVALID_RESPONSE",(result as PracticeResult.Failed).code)
        assertEquals(2,db.learning().pendingForRecords(owner.profileId.toString(),0,listOf(lessonId.toString(),answer.lease.attemptId.toString())).size)
        assertEquals(0,transport.feedbackBodies.size)
    }

    @Test fun getRestoresCompletedUnpresentedFeedbackWithoutCreatingAnotherAttemptOrModelCall() = runBlocking {
        val answer = accepted()
        assertTrue(repository.submit(scope,answer.lease.attemptId,InteractionChannel.PARKED) is PracticeResult.Saved)
        repository.interrupt()
        val restored = repository.restore(scope,answer.lease.attemptId)
        assertTrue(restored.toString(),restored is PracticeResult.Saved)
        val result = restored as PracticeResult.Saved
        assertEquals(answer.lease.attemptId,result.feedback!!.progress.pendingFeedbackAttemptId)
        assertEquals("completed",result.feedback.progress.phase)
        assertEquals(1,transport.feedbackBodies.size)
        assertEquals(1,transport.calls.count { it.first == "GET" })
        assertNull(result.feedback.correctionText)
    }

    @Test fun oldContextResultIsSavedForOriginalAttemptWithoutBecomingCurrentFeedback() = runBlocking {
        val answer = accepted(); transport.afterFeedback = { current = false; repository.interrupt() }
        assertEquals(PracticeResult.Superseded,repository.submit(scope,answer.lease.attemptId,InteractionChannel.PARKED))
        assertNotNull(db.learning().record(owner.profileId.toString(),0,stableRecordId("attempt-result:" + answer.lease.attemptId).toString()))
        assertNull(repository.snapshot(elapsed)!!.feedback)
    }

    @Test fun elapsedWaitDoesNotLoseSavedResultOrPresentItAutomatically() = runBlocking {
        val answer = accepted(); transport.afterFeedback = { elapsed = 10_001 }
        val result = repository.submit(scope,answer.lease.attemptId,InteractionChannel.PARKED) as PracticeResult.Saved
        assertTrue(result.waiting); assertNull(result.feedback)
        assertEquals(PracticeRequestState.WAIT_EXPIRED,repository.snapshot(elapsed)!!.requestState)
        transport.afterFeedback = {}
        val restored = repository.restore(scope,answer.lease.attemptId)
        assertTrue(restored.toString(),restored is PracticeResult.Saved)
        assertNotNull((restored as PracticeResult.Saved).feedback)
    }

    @Test fun feedbackGetDoesNotAcknowledgePresentationAndDefaultVerifierRefusesSyntheticAck() = runBlocking {
        val answer = accepted(); repository.submit(scope,answer.lease.attemptId,InteractionChannel.PARKED)
        val callCount = transport.calls.size
        val result = repository.acknowledgeFeedback(scope,FeedbackPresentationWitness(owner,lessonId,answer.lease.attemptId,"parked_visible",UUID.randomUUID(),"test-only-no-adapter"))
        assertTrue(result is LearningReply.Failed); assertEquals(callCount,transport.calls.size)
    }
}
