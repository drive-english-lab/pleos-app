package io.github.driveenglishlab.pleos.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Strict v2 HTTP codec + actual Room transaction/journal/CAS regressions. Responses
 * are deterministic fixtures, not live source approval, canonical server success or output.
 * The shared-state port records only the passive boundary; this repository owns no audio/UI.
 */
@RunWith(AndroidJUnit4::class)
class ReviewNext2ListeningRepositoryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val owner = OwnerContext(UUID.randomUUID(), 0, 1, UUID.randomUUID())
    private val scope = LearningCallScope(owner, LearningSession(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "synthetic-selection-session"))
    private val acceptedAt = Instant.parse("2026-11-15T01:00:00Z")
    private val stream = UUID.randomUUID()
    private lateinit var db: LearningDatabase
    private lateinit var codecs: IncrementalContractJson
    private lateinit var api: IncrementalLearningApi
    private lateinit var repository: ReviewNext2ListeningRepository
    private lateinit var baseline: JsonObject
    private var live: LearningCallScope? = scope
    private var elapsed = 100L
    private var now = acceptedAt
    private var passiveJoins = 0
    private val posts = mutableListOf<String>()
    private var onPost: suspend (JsonObject) -> LearningHttpResponse = { response(it, "selected") }

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, LearningDatabase::class.java).build()
        db.learning().putOwner(LocalOwner(profileId = owner.profileId.toString(), privacyEpoch = 0, revision = 1, status = "active"))
        codecs = IncrementalContractJson(context)
        baseline = contractFixture("NextArticleResponse") { it["status"] == JsonPrimitive("selected") }
        api = IncrementalLearningApi(LearningSessionSource { live }, codecs, object : LearningHttpTransport {
            override suspend fun exchange(method: String, path: String, query: Map<String,String>, headers: Map<String,String>, body: ByteArray?): LearningHttpResponse {
                assertEquals("post", method.lowercase()); assertEquals("/v2/listening/next", path); assertTrue(query.isEmpty())
                val bytes = requireNotNull(body).toString(Charsets.UTF_8)
                val request = codecs.parse("review2", bytes).jsonObject
                assertEquals(request.getValue("operation_id").jsonPrimitive.content, headers["Idempotency-Key"])
                posts += bytes
                return onPost(request)
            }
        })
        repository = newRepository()
    }
    private fun newRepository() = ReviewNext2ListeningRepository(api, codecs, db, db.serviceIntents(), db.review(),
        ReviewNext2StatePort { supplied, _, _ -> assertEquals(scope, supplied); passiveJoins++; true }, { elapsed }, { now })
    @After fun close() { db.close() }

    private fun request(channel: PlaybackRequestChannel = PlaybackRequestChannel.APP): Pair<WireReview2ReviewNextRequest2, PlaybackRequestBinding> {
        val legacy = baseline.getValue("selection").jsonObject.getValue("content_ref").jsonObject
        val binding = repository.newBinding(owner, UUID.randomUUID(), UUID.randomUUID(), PlaybackRequestKind.EXPLICIT_ARTICLE,
            channel, UUID.fromString(legacy.getValue("article_id").jsonPrimitive.content), UUID.fromString(legacy.getValue("source_snapshot_id").jsonPrimitive.content), ListeningMode.ORIGINAL_FULL)
        return WireReview2ReviewNextRequest2("6", binding.operationId.toString(), WireReview2Source6CommonProfileContext(owner.profileId.toString(), 0),
            stream.toString(), 1, null, 1, UUID.randomUUID().toString(), "explicit_article", binding.requestedArticleId.toString(), "explicit", 0, 2,
            "explicit_article", "issues", binding.requestedSourceSnapshotId.toString(), channel.name.lowercase()) to binding
    }
    private fun guard(binding: PlaybackRequestBinding) = PlaybackActivationGuard(owner, binding.contextToken, repository.processToken, elapsed, now, true)

    private fun response(request: JsonObject, state: String, version: Long = 1, selectedId: String = UUID.randomUUID().toString()): LearningHttpResponse = jsonResponse(envelope(request, state, version, selectedId))
    private fun envelope(request: JsonObject, state: String, version: Long = 1, selectedId: String = UUID.randomUUID().toString()): JsonObject {
        val selected = baseline.getValue("selection").jsonObject
        val original = baseline.getValue("checkpoint").jsonObject.getValue("payload").jsonObject
        val ref = buildJsonObject { put("source_kind", "legacy_bundle"); put("legacy_ref", selected.getValue("content_ref")) }
        val active = state in setOf("selected", "resumed", "pending", "expired_waiting_explicit")
        val selectedState = state in setOf("selected", "resumed")
        val channelBudget = if (request["request_channel"] == JsonPrimitive("gleo")) 6_000L else 10_000L
        val requestHash = LearningJson.hash(incrementalCanonical(request))
        return buildJsonObject {
            put("schema_version", "6"); put("review_contract_version", 2); put("operation_id", request.getValue("operation_id"))
            put("profile_context", request.getValue("profile_context")); put("stream_id", request.getValue("stream_id"))
            putJsonObject("admission_receipt") {
                put("operation_id", request.getValue("operation_id")); put("request_hash", requestHash); put("accepted_at", acceptedAt.toString())
                put("intent_kind", request.getValue("intent_kind"))
                // The selection-intent ID and deadline are immutable for this operation.
                put("selection_intent_id", if (active) request.getValue("operation_id") else JsonNull)
                put("activation_deadline_at", if (active) JsonPrimitive(acceptedAt.plusMillis(channelBudget).toString()) else JsonNull)
            }
            putJsonObject("outcome_projection") {
                put("version", version); put("observed_at", now.toString()); put("state", state); put("stream_version", 2)
                put("selection", if (selectedState) selected.changed("selection_id" to JsonPrimitive(selectedId), "stream_id" to request.getValue("stream_id"),
                    "content_ref" to ref, "selection_reason" to JsonPrimitive("explicit_article")) else JsonNull)
                put("checkpoint", if (selectedState) buildJsonObject {
                    put("record_id", request.getValue("checkpoint_id")); put("kind", "checkpoint"); put("version", 1)
                    putJsonObject("payload") {
                        put("checkpoint_id", request.getValue("checkpoint_id")); put("stream_id", request.getValue("stream_id")); put("selection_id", selectedId)
                        put("content_ref", ref); put("active_run_id", JsonNull); put("main_coverage_status", "unknown")
                        putJsonObject("cursor") { put("kind", "legacy_time"); put("original_checkpoint_id", request.getValue("checkpoint_id"));
                            put("original_position_ms", 0); put("original_duration_ms", original.getValue("duration_ms")); put("phase", "main") }
                    }
                } else JsonNull)
                put("context", if (selectedState) buildJsonObject {
                    put("selection_id", selectedId); put("profile_id", owner.profileId.toString()); put("learning_epoch", 0); put("version", 1)
                    put("origin", "regular"); put("end_policy", "regular_auto")
                    listOf("offer_id", "cancelled_offer_id", "source_selection_id", "previous_resume_ref", "return_snapshot", "practice_entry").forEach { put(it, JsonNull) }
                    putJsonObject("auto_progress_ref") { put("auto_progress_id", request.getValue("stream_id")); put("version", 2) }
                } else JsonNull)
                put("source_status", if (selectedState) buildJsonArray { add(buildJsonObject {
                    put("key", "a".repeat(64)); put("kind", "main_original"); put("status", "ready"); put("component_id", UUID.randomUUID().toString())
                    put("payload_hash", "b".repeat(64)); put("reason", JsonNull); put("retryable", false); put("retry_after", JsonNull); put("validation_revision", "synthetic-structure-only")
                }) } else JsonArray(emptyList()))
                put("no_candidate_reason", JsonNull)
            }
        }
    }
    private fun payload(request: WireReview2ReviewNextRequest2) = codecs.parse("review2", codecs.encode("review2", "ReviewNextRequest2", request, WireReview2ReviewNextRequest2.serializer())).jsonObject

    @Test fun cancellationCommitsWhileHttpIsPendingAndLateSelectedCannotClaimActivation() = runBlocking {
        val (request,binding) = request(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        onPost = { entered.complete(Unit); release.await(); response(it,"selected") }
        val call = async { repository.submit(scope,request,binding,guard(binding)) }
        withTimeout(3_000) { entered.await() }
        assertTrue(repository.cancel(scope,binding.operationId)) // Must not wait behind the HTTP lane.
        release.complete(Unit)
        val result = call.await() as LearningReply.Stored
        assertEquals(PlaybackRequestState.CANCELLED,result.value.intent!!.state)
        val row = requireNotNull(db.serviceIntents().intent(request.operationId))
        assertEquals("cancelled",row.state); assertEquals("acknowledged",db.review().operation(request.operationId)!!.status)
        assertNotNull(row.admissionJson); assertEquals(1L,row.outcomeVersion)
        assertNull(repository.claimActivation(scope,binding.operationId,guard(binding)))
        assertTrue(result.value.requiresExplicitResume)
        // A passive canonical join may retain the selected server state; it grants no output.
        assertEquals(1,passiveJoins); assertEquals(1,posts.size)
    }
    @Test fun lostAdmissionObservesExactlyOriginalJournalBytesAndRejectsRebasedInput() = runBlocking {
        val (request,binding) = request()
        onPost = { throw java.io.IOException("synthetic lost reply") }
        assertTrue(repository.submit(scope,request,binding,guard(binding)) is LearningReply.Failed)
        val original = db.review().operation(request.operationId)!!
        onPost = { response(it,"pending") }
        assertTrue(repository.observe(scope,binding.operationId,guard(binding)) is LearningReply.Stored)
        assertEquals(posts[0],posts[1]); assertEquals(original.payloadJson,posts[1])
        val acknowledged = db.review().operation(request.operationId)!!
        assertEquals(original.payloadHash,acknowledged.payloadHash); assertEquals("acknowledged",acknowledged.status)
        assertTrue(repository.submit(scope,request.copy(checkpointId = UUID.randomUUID().toString()),binding,guard(binding)) is LearningReply.Failed)
        assertEquals(2,posts.size); assertEquals(original.payloadJson,db.review().operation(request.operationId)!!.payloadJson)
    }
    @Test fun originalServerSixAndTenSecondWindowsBoundObservationWithoutRenewal() = runBlocking {
        for (channel in PlaybackRequestChannel.entries) {
            elapsed = 100; now = acceptedAt
            val (request,binding) = request(channel)
            onPost = { response(it,"pending") }
            repository.submit(scope,request,binding,guard(binding))
            val admitted = db.serviceIntents().intent(request.operationId)!!; val before = posts.size
            now = acceptedAt.plusMillis(channel.budgetMs + 1) // Local monotonic clock still inside its window.
            val result = repository.observe(scope,binding.operationId,guard(binding)) as LearningReply.Stored
            assertEquals(PlaybackRequestState.EXPIRED,result.value.intent!!.state)
            assertEquals(before,posts.size)
            val after = db.serviceIntents().intent(request.operationId)!!
            assertEquals(admitted.bindingJson,after.bindingJson); assertEquals(admitted.admissionJson,after.admissionJson)
            assertEquals(binding.localDeadlineElapsedMs,result.value.intent!!.binding.localDeadlineElapsedMs)
            assertNull(repository.claimActivation(scope,binding.operationId,guard(binding)))
        }
    }
    @Test fun selectedArrivingAfterOriginalLocalDeadlineIsStoredButCannotActivate() = runBlocking {
        val (request,binding) = request()
        onPost = { elapsed = binding.localDeadlineElapsedMs + 1; response(it,"selected") }
        val result = repository.submit(scope,request,binding,guard(binding)) as LearningReply.Stored
        assertEquals(PlaybackRequestState.EXPIRED,result.value.intent!!.state)
        assertEquals(PlaybackRequestOutcome.SELECTED,result.value.intent!!.outcome)
        assertNotNull(db.serviceIntents().intent(request.operationId)!!.outcomeJson)
        assertNull(repository.claimActivation(scope,binding.operationId,guard(binding)))
    }
    @Test fun epochChangeDuringHttpRejectsLateSelectedWithoutJoiningOrAcknowledgingOldOwner() = runBlocking {
        val (request,binding) = request(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        onPost = { entered.complete(Unit); release.await(); response(it,"selected") }
        val call = async { repository.submit(scope,request,binding,guard(binding)) }
        withTimeout(3_000) { entered.await() }
        val replacement = owner.copy(privacyEpoch = 1,revision = 2)
        live = scope.copy(owner = replacement)
        db.learning().putOwner(LocalOwner(profileId = owner.profileId.toString(),privacyEpoch = 1,revision = 2,status = "active"))
        repository.clear(); release.complete(Unit)
        assertEquals("SESSION_CHANGED",(call.await() as LearningReply.Failed).code)
        assertEquals(0,passiveJoins); assertNull(repository.state.value.owner)
        assertNull(db.serviceIntents().intent(request.operationId)!!.outcomeJson)
        assertEquals("pending",db.review().operation(request.operationId)!!.status)
    }
    @Test fun plainCompletedArticleClickCannotJoinOrActivateOrAllocateAnExplicitReplay() = runBlocking {
        val (request,binding) = request()
        onPost = { response(it,"requires_explicit_replay") }
        val result = repository.submit(scope,request,binding,guard(binding)) as LearningReply.Stored
        assertEquals(PlaybackRequestOutcome.REQUIRES_EXPLICIT_REPLAY,result.value.intent!!.outcome)
        assertNull(result.value.response!!.admissionReceipt.selectionIntentId)
        assertEquals(0,passiveJoins); assertNull(repository.claimActivation(scope,binding.operationId,guard(binding)))
        repository.observe(scope,binding.operationId,guard(binding))
        assertEquals(1,posts.size); assertEquals("explicit_article",payload(request).getValue("selection_kind").jsonPrimitive.content)
        assertEquals(posts.single(),db.review().operation(request.operationId)!!.payloadJson)
    }
    @Test fun logicalHeadRequiresAnAcknowledgedExactProofAndConflictingHeadsFailClosed() = runBlocking {
        val recovery = AutoProgressHeadRecoveryRepository(api,codecs,db.serviceIntents(),db.review())
        assertEquals("CONFIRMED_LOGICAL_HEAD_REQUIRED",(recovery.recover(scope,stream,2) as AutoProgressHeadRecovery.Unavailable).reason)
        val (first,binding) = request(); repository.submit(scope,first,binding,guard(binding))
        val confirmed = recovery.recover(scope,stream,2) as AutoProgressHeadRecovery.Confirmed
        assertEquals(binding.operationId,confirmed.head.proofOperationId); assertEquals(stream,confirmed.head.autoProgressId)
        assertEquals("CONFIRMED_LOGICAL_HEAD_REQUIRED",(recovery.recover(scope,stream,3) as AutoProgressHeadRecovery.Unavailable).reason)
        // Two structurally valid but contradictory local server receipts are not a head proof.
        val (second,nextBinding) = request(); repository.submit(scope,second,nextBinding,guard(nextBinding))
        assertEquals("CONTRADICTORY_LOGICAL_HEAD",(recovery.recover(scope,stream,2) as AutoProgressHeadRecovery.Unavailable).reason)
        live = null
        assertEquals("SESSION_CHANGED",(recovery.recover(scope,stream,2) as AutoProgressHeadRecovery.Unavailable).reason)
    }
    @Test fun restoreInAnotherProcessKeepsOutcomeButNeverReconstitutesAnOutputGrant() = runBlocking {
        val (request,binding) = request(); repository.submit(scope,request,binding,guard(binding))
        val before = db.serviceIntents().intent(request.operationId)!!
        val nextProcess = newRepository()
        val result = nextProcess.restore(scope,binding.operationId) as LearningReply.Stored
        assertEquals(PlaybackRequestState.EXPIRED,result.value.intent!!.state)
        assertTrue(result.value.requiresExplicitResume); assertFalse(result.value.businessStateCurrent)
        assertEquals(before.admissionJson,db.serviceIntents().intent(request.operationId)!!.admissionJson)
        assertNull(nextProcess.claimActivation(scope,binding.operationId,guard(binding).copy(processToken = nextProcess.processToken)))
        assertEquals(1,posts.size)
    }
}
