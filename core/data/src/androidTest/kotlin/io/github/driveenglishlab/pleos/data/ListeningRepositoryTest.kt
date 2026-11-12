package io.github.driveenglishlab.pleos.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ListeningRepositoryTest {
    private lateinit var db: LearningDatabase
    private lateinit var repo: ListeningRepository
    private lateinit var transport: Transport
    private val owner = OwnerContext(UUID.randomUUID(),0,1,UUID.randomUUID())
    private val scope = LearningCallScope(owner,LearningSession(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"test-user-session"))
    @Before fun setup() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context,LearningDatabase::class.java).build()
        db.learning().putOwner(LocalOwner(profileId = owner.profileId.toString(),privacyEpoch = 0,revision = 1,status = "active"))
        val contracts = LearningContractJson(context); transport = Transport()
        repo = ListeningRepository(LearningApi(LearningSessionSource { scope },contracts,transport),db.learning(),contracts,{ it == owner },ListeningTransitionPort { true })
        assertTrue(repo.createStream(scope,1,1,startedAt = Instant.EPOCH) is ListeningResult.Ready)
    }
    @After fun close() { db.close() }
    private fun point(frame: ListeningFrame): WirePersonalCheckpointInput {
        val json = learningJson.encodeToJsonElement(WirePersonalCheckpointRecord.serializer(),frame.checkpoint!!.payload).jsonObject
        return learningJson.decodeFromJsonElement(WirePersonalCheckpointInput.serializer(),JsonObject(json.filterKeys { it !in setOf("active_run_id","main_coverage_status") }))
    }
    private inner class Transport : LearningHttpTransport {
        val paths = mutableListOf<String>()
        val requests = mutableListOf<Pair<String,JsonObject>>()
        var fail: String? = null
        var noCandidate = false
        var beforeReply: suspend (String) -> Unit = {}
        override suspend fun exchange(method: String, path: String, query: Map<String,String>, headers: Map<String,String>, body: ByteArray?): LearningHttpResponse {
            paths += path
            val request = learningJson.parseToJsonElement(body!!.toString(Charsets.UTF_8)).jsonObject
            requests += path to request
            if (path == fail) return jsonResponse(buildJsonObject {
                put("schema_version","5"); put("operation_id",request.getValue("operation_id"))
                putJsonObject("error") { put("code","UPSTREAM_UNAVAILABLE"); put("message_ko","저장한 위치를 유지합니다."); put("retryable",true) }
            },503)
            val response = when (path) {
                "/v1/profile/sync" -> buildJsonObject {
                    put("schema_version","5"); put("profile_context",request.getValue("profile_context")); put("cursor","fixture-page")
                    put("receipts",JsonArray(request.getValue("events").jsonArray.map { item ->
                        val event = item.jsonObject; val payload = event.getValue("payload").jsonObject
                        val idField = when (event.getValue("kind").jsonPrimitive.content) {
                            "stream" -> "stream_id"; "checkpoint" -> "checkpoint_id"; "playback_observation" -> "observation_id"; else -> error("Unexpected event.")
                        }
                        buildJsonObject {
                            put("event_id",event.getValue("event_id")); put("record_id",payload.getValue(idField))
                            val previous = event.getValue("expected_record_version").jsonPrimitive.longOrNull
                            put("version",(previous ?: 0) + 1); put("outcome",if (previous == null) "created" else "updated")
                        }
                    }))
                }
                "/v1/listening/next" -> {
                    // The stream sync receipt must already be durable before selection is requested.
                    assertNotNull(db.learning().record(owner.profileId.toString(),0,stableRecordId("server-record:" + request.getValue("stream_id").jsonPrimitive.content).toString()))
                    val base = contractFixture("NextArticleResponse") { it.getValue("status").jsonPrimitive.content == "selected" }
                    if (noCandidate) base.changed("operation_id" to request.getValue("operation_id"),"profile_context" to request.getValue("profile_context"),
                        "stream_id" to request.getValue("stream_id"),"stream_version" to request.getValue("expected_stream_version"),"status" to JsonPrimitive("no_candidate"),
                        "selection" to JsonNull,"checkpoint" to JsonNull,"no_candidate_reason" to JsonPrimitive("all_listened"))
                    else {
                        val selection = base.getValue("selection").jsonObject.changed("selection_id" to JsonPrimitive(UUID.randomUUID().toString()),
                            "stream_id" to request.getValue("stream_id"),"previous_selection_id" to request.getValue("previous_selection_id"))
                        val checkpoint = base.getValue("checkpoint").jsonObject
                        val payload = checkpoint.getValue("payload").jsonObject.changed("checkpoint_id" to request.getValue("checkpoint_id"),
                            "stream_id" to request.getValue("stream_id"),"selection_id" to selection.getValue("selection_id"),"active_run_id" to JsonNull)
                        base.changed("operation_id" to request.getValue("operation_id"),"profile_context" to request.getValue("profile_context"),"stream_id" to request.getValue("stream_id"),
                            "stream_version" to JsonPrimitive(request.getValue("expected_stream_version").jsonPrimitive.long + 1),"selection" to selection,
                            "checkpoint" to checkpoint.changed("record_id" to request.getValue("checkpoint_id"),"payload" to payload))
                    }
                }
                "/v1/playback-runs" -> {
                    val checkpoint = repo.snapshot()!!.checkpoint!!; val runId = UUID.randomUUID().toString()
                    val payload = checkpoint.payload.copy(activeRunId = runId)
                    buildJsonObject {
                        put("schema_version","5"); put("operation_id",request.getValue("operation_id")); put("profile_context",request.getValue("profile_context"))
                        putJsonObject("run") { put("run_id",runId); put("checkpoint_id",checkpoint.recordId); put("main_audio_asset_id",checkpoint.payload.currentAssetId!!)
                            put("initial_frontier_ms",0); put("confirmed_frontier_ms",0); put("applied_sequence_no",0) }
                        put("checkpoint",learningJson.encodeToJsonElement(WirePersonalReadCheckpoint.serializer(),checkpoint.copy(version = checkpoint.version + 1,payload = payload)))
                    }
                }
                "/v1/profile/resume" -> contractFixture("ProfileView").changed("profile_context" to request.getValue("profile_context"),
                    "active_resume" to request.getValue("active_resume"),"resume_version" to JsonPrimitive(request.getValue("expected_resume_version").jsonPrimitive.long + 1))
                "/v1/listening/format-changes" -> {
                    val source = requireNotNull(formatSource)
                    val selectionId = UUID.randomUUID().toString()
                    val content = learningJson.encodeToJsonElement(WireCommonContentRef.serializer(),source.contentRef).jsonObject.changed(
                        "listening_mode" to request.getValue("target_listening_mode"),"listening_script_id" to JsonPrimitive(UUID.randomUUID().toString()))
                    val selected = learningJson.encodeToJsonElement(WirePersonalSelectionRecord.serializer(),source).jsonObject.changed(
                        "ordinal" to JsonPrimitive(repo.snapshot()!!.selection!!.ordinal + 1),
                        "selection_id" to JsonPrimitive(selectionId),"previous_selection_id" to request.getValue("expected_head_selection_id"),"selection_reason" to JsonPrimitive("explicit_format_change"),
                        "content_ref" to content,"settings_snapshot" to learningJson.encodeToJsonElement(WireCommonSettings.serializer(),source.settingsSnapshot.copy(listeningMode = request.getValue("target_listening_mode").jsonPrimitive.content)),
                        "format_change_source" to buildJsonObject { put("selection_id",request.getValue("source_selection_id")); put("checkpoint_id",request.getValue("source_checkpoint_id")) })
                    val base = contractFixture("NextArticleResponse") { it.getValue("status").jsonPrimitive.content == "selected" }.getValue("checkpoint").jsonObject
                    val payload = base.getValue("payload").jsonObject.changed("checkpoint_id" to request.getValue("checkpoint_id"),"content_ref" to content,
                        "phase" to JsonPrimitive("main"),"position_ms" to JsonPrimitive(0),"active_run_id" to JsonNull,"main_coverage_status" to JsonPrimitive("unknown"),
                        "stream_id" to request.getValue("stream_id"),"selection_id" to JsonPrimitive(selectionId))
                    buildJsonObject {
                        put("schema_version","5"); put("operation_id",request.getValue("operation_id")); put("profile_context",request.getValue("profile_context"))
                        put("stream_id",request.getValue("stream_id")); put("stream_version",request.getValue("expected_stream_version").jsonPrimitive.long + 1)
                        put("selection",selected); put("checkpoint",base.changed("record_id" to request.getValue("checkpoint_id"),"payload" to payload))
                    }
                }
                else -> error("Unexpected listening request.")
            }
            beforeReply(path)
            return jsonResponse(response)
        }
        var formatSource: WirePersonalSelectionRecord? = null
    }

    @Test fun streamAckThenSelectionRunObservationReceiptThenExplicitResumeCas() = runBlocking {
        assertTrue(repo.next(scope,null) is ListeningResult.Ready)
        assertTrue(repo.startRun(scope) is ListeningResult.Ready)
        val frame = repo.snapshot()!!; val observation = UUID.randomUUID().toString()
        repo.observe(scope,WirePersonalPlaybackObservationInput(observation,frame.checkpoint!!.payload.activeRunId!!,1,"observed",WirePersonalPlaybackObservationInputRange(0,1_000)))
        val result = repo.selectResume(scope)
        assertTrue(result.toString(),result is ListeningResult.Ready)
        assertEquals(listOf("/v1/profile/sync","/v1/listening/next","/v1/playback-runs","/v1/profile/sync","/v1/profile/resume"),transport.paths)
        assertTrue(db.learning().pendingSync(owner.profileId.toString(),0).isEmpty())
        val cas = transport.requests.last().second
        assertEquals(1L,cas.getValue("expected_resume_version").jsonPrimitive.long)
        assertEquals("listening",cas.getValue("active_resume").jsonObject.getValue("kind").jsonPrimitive.content)
    }

    @Test fun olderSourceFormatUsesCurrentHeadFenceAndPinnedSourceFollowupAtZero() = runBlocking {
        repo.next(scope,null); val sourceFrame = repo.snapshot()!!; val source = sourceFrame.selection!!
        val head = source.copy(selectionId = UUID.randomUUID().toString(),previousSelectionId = source.selectionId,ordinal = source.ordinal + 1)
        val headPoint = sourceFrame.checkpoint!!.copy(recordId = UUID.randomUUID().toString()).let { it.copy(payload = it.payload.copy(checkpointId = it.recordId,selectionId = head.selectionId)) }
        repo.attach(sourceFrame.copy(streamVersion = sourceFrame.streamVersion + 1,selection = head,checkpoint = headPoint))
        repo.updateSettingsVersion(owner,99); transport.formatSource = source
        val result = repo.changeFormat(scope,source,point(sourceFrame),sourceFrame.checkpoint.version,ListeningMode.BRIEFING)
        assertTrue(result.toString(),result is ListeningResult.Ready)
        val request = transport.requests.last().second
        assertEquals(head.selectionId,request.getValue("expected_head_selection_id").jsonPrimitive.content)
        assertEquals(source.selectionId,request.getValue("source_selection_id").jsonPrimitive.content)
        val current = repo.snapshot()!!
        assertEquals(0L,current.checkpoint!!.payload.positionMs); assertNull(current.checkpoint.payload.activeRunId)
        assertEquals(source.settingsSnapshot.followupPolicy,current.selection!!.settingsSnapshot.followupPolicy)
        assertEquals(99L,current.settingsVersion)
    }

    @Test fun failedFormatKeepsOriginalFrameAndDurableSavedInputPoint() = runBlocking {
        repo.next(scope,null); val before = repo.snapshot()!!; transport.fail = "/v1/listening/format-changes"
        val point = point(before).copy(positionMs = 1_234)
        val result = repo.changeFormat(scope,before.selection!!,point,before.checkpoint!!.version,ListeningMode.BRIEFING)
        assertTrue(result is ListeningResult.Failed); assertEquals(before,repo.snapshot())
        val saved = db.learning().record(owner.profileId.toString(),0,stableRecordId("local-input:" + point.checkpointId).toString())!!
        assertEquals(1_234L,learningJson.parseToJsonElement(saved.payloadJson).jsonObject.getValue("position_ms").jsonPrimitive.long)
    }

    @Test fun explicitNextRetryReusesSavedBodyAndLateReceiptCannotReplaceCurrentWork() = runBlocking {
        transport.fail = "/v1/listening/next"
        val failed = repo.next(scope,null) as ListeningResult.Failed
        val request = transport.requests.last().second
        transport.fail = null
        val before = repo.snapshot()!!
        transport.beforeReply = { if (it == "/v1/listening/next") repo.attach(before) }
        assertEquals(ListeningResult.Superseded,repo.retryNext(scope,failed.operationId))
        assertEquals(request,transport.requests.last().second); assertEquals(before,repo.snapshot())
        assertNotNull(db.learning().record(owner.profileId.toString(),0,stableRecordId("receipt:" + failed.operationId).toString()))
    }

    @Test fun noCandidatePreservesWorkAndDoesNotCreateNewStreamOrPlaybackRun() = runBlocking {
        repo.next(scope,null); val before = repo.snapshot()!!; transport.noCandidate = true
        val result = repo.next(scope,point(before)) as ListeningResult.NoCandidate
        assertEquals("all_listened",result.reason); assertEquals(before,repo.snapshot())
        assertEquals(0,transport.paths.count { it == "/v1/playback-runs" })
        assertTrue(repo.createStream(scope,1,1) is ListeningResult.Failed)
    }
}
