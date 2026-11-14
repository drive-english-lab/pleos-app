package io.github.driveenglishlab.pleos.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LearningApiTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun cases() = InstrumentationRegistry.getInstrumentation().context.assets.open("learning-contract-cases.json")
        .bufferedReader().use { learningJson.parseToJsonElement(it.readText()).jsonArray }
    private fun example(name: String) = cases().first {
        it.jsonObject["valid"] == JsonPrimitive(true) && it.jsonObject["pointer"]!!.jsonPrimitive.content.endsWith("/$name")
    }.jsonObject["payload"]!!
    private fun scope(profile: JsonObject): LearningCallScope {
        val owner = OwnerContext(UUID.fromString(profile["profile_id"]!!.jsonPrimitive.content), profile["privacy_epoch"]!!.jsonPrimitive.long,
            profile["context_revision"]?.jsonPrimitive?.long ?: 1, UUID.randomUUID())
        return LearningCallScope(owner, LearningSession(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "synthetic-user-session-token"))
    }
    private class FakeTransport(val response: suspend () -> LearningHttpResponse) : LearningHttpTransport {
        val headers = mutableListOf<Map<String, String>>(); val bodies = mutableListOf<ByteArray?>()
        override suspend fun exchange(method: String, path: String, query: Map<String, String>, headers: Map<String, String>, body: ByteArray?): LearningHttpResponse {
            this.headers += headers.toMap(); bodies += body?.copyOf(); return response()
        }
    }
    private fun response(value: JsonElement, status: Int = 200, extra: Map<String, String> = emptyMap()) =
        LearningHttpResponse(status, mapOf("Content-Type" to "application/json; charset=utf-8") + extra, value.toString().toByteArray(Charsets.UTF_8))
    private fun scopedFeedback(): Pair<WireInferenceFeedbackRequest, LearningCallScope> {
        val fixture = learningJson.decodeFromJsonElement(WireInferenceFeedbackRequest.serializer(), example("FeedbackRequest"))
        val scope = scope(learningJson.encodeToJsonElement(WireCommonClientContext.serializer(), fixture.clientContext).jsonObject)
        // The wire contract permits opaque tokens; this app creates its own fresh UUID token.
        return fixture.copy(clientContext = fixture.clientContext.copy(presentationToken = scope.owner.presentationToken.toString())) to scope
    }

    @Test fun mergeExpiryNeedsTheExactOriginalOperationAndConflictStatus() = runBlocking {
        val request = WirePersonalMergeComplete(UUID.randomUUID().toString(), WireCommonProfileContext(UUID.randomUUID().toString(), 0),
            UUID.randomUUID().toString(), "synthetic-merge-proof-" + "x".repeat(32), "5")
        val call = scope(learningJson.encodeToJsonElement(WireCommonProfileContext.serializer(), request.profileContext).jsonObject)
        for ((operation, status, expected) in listOf(
            Triple(request.operationId, 409, "MERGE_EXPIRED"),
            Triple(null, 409, "INVALID_CONTRACT"),
            Triple(UUID.randomUUID().toString(), 409, "INVALID_CONTRACT"),
            Triple(request.operationId, 503, "INVALID_CONTRACT"),
        )) {
            val envelope = buildJsonObject {
                put("schema_version", "5"); put("operation_id", operation?.let(::JsonPrimitive) ?: JsonNull)
                put("error", buildJsonObject { put("code", "MERGE_EXPIRED"); put("retryable", false); put("message_ko", "병합 준비를 다시 확인해 주세요") })
            }
            val transport = FakeTransport { response(envelope, status) }
            val api = LearningApi(LearningSessionSource { call }, LearningContractJson(context), transport)
            val result = api.completeMerge(call, request) as LearningReply.Failed
            assertEquals(expected, result.code)
            assertEquals(request.operationId, transport.headers.single()["Idempotency-Key"])
        }
    }

    @Test fun sharedPositiveAndNegativeVectorsMatchTheServerSchema5Boundaries() {
        val contract = LearningContractJson(context); var positives = 0; var negatives = 0
        for (item in cases()) {
            val row = item.jsonObject; var valid = true
            try { contract.validate(row["document"]!!.jsonPrimitive.content,
                contract.schema(row["document"]!!.jsonPrimitive.content, row["pointer"]!!.jsonPrimitive.content), row["payload"]!!) }
            catch (_: LearningProtocolException) { valid = false }
            assertEquals(row["label"]!!.jsonPrimitive.content, row["valid"]!!.jsonPrimitive.boolean, valid)
            if (valid) positives++ else negatives++
        }
        assertEquals(80, positives); assertEquals(102, negatives)
    }

    @Test fun typedRecordAndSyncUnionsRoundTripWithoutAddingDiscriminatorsOrDefaults() {
        for (item in cases()) {
            val row = item.jsonObject
            if (row["valid"] != JsonPrimitive(true)) continue
            val value = row["payload"]!!
            when (row["pointer"]!!.jsonPrimitive.content.substringAfterLast('/')) {
                "ReadRecord" -> assertEquals(value, learningJson.encodeToJsonElement(WirePersonalReadRecordSerializer,
                    learningJson.decodeFromJsonElement(WirePersonalReadRecordSerializer, value)))
                "SyncEvent" -> assertEquals(value, learningJson.encodeToJsonElement(WirePersonalSyncEventSerializer,
                    learningJson.decodeFromJsonElement(WirePersonalSyncEventSerializer, value)))
            }
        }
    }

    @Test fun requiredNullableEvidenceIsNotSilentlyReplacedWithMissingNull() {
        val capture = example("Capture").jsonObject
        val missing = JsonObject(capture - "language_evidence_ref")
        assertThrows(SerializationException::class.java) { learningJson.decodeFromJsonElement(WireCommonCapture.serializer(), missing) }
        val contract = LearningContractJson(context)
        assertThrows(LearningProtocolException::class.java) { contract.parse("{\"a\":1,\"\\u0061\":2}") }
        assertEquals(JsonPrimitive(1), contract.parse("{\"a\":{\"a\":1}}").jsonObject["a"]!!.jsonObject["a"])
    }

    @Test fun bootstrapAttachesUserSessionAndStableIdempotencyWhileReturningStorageOnly() = runBlocking {
        val profile = example("ProfileView"); val scope = scope(profile.jsonObject["profile_context"]!!.jsonObject)
        val transport = FakeTransport { response(profile) }
        val api = LearningApi(LearningSessionSource { scope }, LearningContractJson(context), transport)
        val operation = UUID.randomUUID().toString()
        val result = api.bootstrapProfile(scope, WirePersonalBootstrapRequest(operationId = operation, provisionalProfileId = null, schemaVersion = "5"))
        assertTrue(result is LearningReply.Stored)
        assertEquals(scope, (result as LearningReply.Stored).scope)
        assertEquals(operation, transport.headers.single()["Idempotency-Key"])
        assertEquals(scope.session.installationId.toString(), transport.headers.single()["X-Installation-ID"])
        assertEquals("5", learningJson.parseToJsonElement(transport.bodies.single()!!.toString(Charsets.UTF_8)).jsonObject["schema_version"]!!.jsonPrimitive.content)
        assertFalse(scope.session.toString().contains("synthetic-user-session-token"))
        assertEquals("LearningReply.Stored(payload=redacted)", result.toString())
    }

    @Test fun ownerChangeDuringRequestDoesNotReturnOldProfilePayloadToNewOwner() = runBlocking {
        val profile = example("ProfileView"); val original = scope(profile.jsonObject["profile_context"]!!.jsonObject)
        var current = original
        val transport = FakeTransport {
            current = original.copy(owner = original.owner.copy(profileId = UUID.randomUUID(), revision = 2))
            response(profile)
        }
        val api = LearningApi(LearningSessionSource { current }, LearningContractJson(context), transport)
        assertEquals(LearningReply.Failed("SESSION_CHANGED", false), api.readProfile(original))
    }

    @Test fun structurallyValidForeignOwnerResponseIsRejected() = runBlocking {
        val profile = example("ProfileView"); val current = scope(profile.jsonObject["profile_context"]!!.jsonObject)
        val foreignContext = JsonObject(profile.jsonObject["profile_context"]!!.jsonObject + ("profile_id" to JsonPrimitive(UUID.randomUUID().toString())))
        val foreign = JsonObject(profile.jsonObject + ("profile_context" to foreignContext))
        val api = LearningApi(LearningSessionSource { current }, LearningContractJson(context), FakeTransport { response(foreign) })
        assertEquals(LearningReply.Failed("INVALID_CONTRACT", false), api.readProfile(current))
    }

    @Test fun inconsistentEnglishClaimIsRejectedBeforeAnyNetworkCall() = runBlocking {
        val (request, scope) = scopedFeedback()
        val transport = FakeTransport { throw AssertionError("Invalid request must never leave the device.") }
        val api = LearningApi(LearningSessionSource { scope }, LearningContractJson(context), transport)
        val result = api.evaluateSpeakingAttempt(scope, request.copy(capture = request.capture.copy(spokenLanguage = "ko", englishEvidence = "verified_path")))
        assertEquals(LearningReply.Failed("INVALID_CONTRACT", false), result); assertTrue(transport.bodies.isEmpty())
    }

    @Test fun retryIsExplicitAndKeepsOriginalOperationAndPayload() = runBlocking {
        val profile = example("ProfileView"); val scope = scope(profile.jsonObject["profile_context"]!!.jsonObject)
        val operation = UUID.randomUUID().toString()
        val error = buildJsonObject {
            put("schema_version", "5"); put("operation_id", operation)
            putJsonObject("error") { put("code", "UPSTREAM_UNAVAILABLE"); put("retryable", true); put("message_ko", "잠시 후 다시 시도해 주세요.") }
        }
        val transport = FakeTransport { response(error, 503, mapOf("Retry-After" to "3")) }
        val api = LearningApi(LearningSessionSource { scope }, LearningContractJson(context), transport)
        val request = WirePersonalBootstrapRequest(operationId = operation, provisionalProfileId = null, schemaVersion = "5")
        assertEquals(LearningReply.Failed("UPSTREAM_UNAVAILABLE", true, 3), api.bootstrapProfile(scope, request))
        assertEquals(1, transport.bodies.size)
        api.bootstrapProfile(scope, request)
        assertArrayEquals(transport.bodies[0], transport.bodies[1])
        assertEquals(transport.headers[0]["Idempotency-Key"], transport.headers[1]["Idempotency-Key"])
    }

    @Test fun utf8ByteBudgetIsSeparateFromValidUnicodeFieldLengths() = runBlocking {
        val (request, scope) = scopedFeedback()
        val transport = FakeTransport { throw AssertionError("Oversize request must not leave the device.") }
        val api = LearningApi(LearningSessionSource { scope }, LearningContractJson(context), transport)
        val large = request.copy(capture = request.capture.copy(uncertainSpans = List(8) { "$it" + "😀".repeat(239) }),
            supportRefs = List(16) { "r$it" + "😀".repeat(157) })
        assertEquals(LearningReply.Failed("REQUEST_TOO_LARGE", false), api.evaluateSpeakingAttempt(scope, large))
        assertTrue(transport.bodies.isEmpty())
    }
}
