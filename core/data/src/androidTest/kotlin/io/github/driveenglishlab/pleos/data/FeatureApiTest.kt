package io.github.driveenglishlab.pleos.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FeatureApiTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun contracts() = LearningContractJson(context, "feature-contracts", featureContractDocuments)
    private fun cases() = InstrumentationRegistry.getInstrumentation().context.assets.open("feature-contract-cases.json")
        .bufferedReader().use { featureJson.parseToJsonElement(it.readText()).jsonArray }
    private fun example(name: String, index: Int = 0) = cases().filter {
        it.jsonObject["valid"] == JsonPrimitive(true) && it.jsonObject["pointer"]!!.jsonPrimitive.content.endsWith("/$name")
    }[index].jsonObject["payload"]!!
    private fun scope(profile: String) = LearningCallScope(OwnerContext(UUID.fromString(profile), 0, 1, UUID.randomUUID()),
        LearningSession(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "synthetic-feature-test-token"))
    private class Transport(val reply: suspend () -> LearningHttpResponse) : LearningHttpTransport {
        var calls = 0
        var lastHeaders: Map<String, String> = emptyMap()
        override suspend fun exchange(method: String, path: String, query: Map<String, String>, headers: Map<String, String>, body: ByteArray?): LearningHttpResponse {
            calls++; lastHeaders = headers; return reply()
        }
    }
    private fun response(payload: JsonElement, status: Int = 200) = LearningHttpResponse(status,
        mapOf("Content-Type" to "application/json"), payload.toString().toByteArray())
    private fun client(scope: LearningCallScope, transport: Transport) = LearningApi(LearningSessionSource { scope }, contracts(), transport, featureJson)

    @Test fun featureExamplesAndMissingRequiredAndUnknownFieldsAreValidatedFromPackagedAssets() {
        val contract = contracts(); var positive = 0; var negative = 0
        for (item in cases()) {
            val row = item.jsonObject; var valid = true
            try { contract.validate(row["document"]!!.jsonPrimitive.content,
                contract.schema(row["document"]!!.jsonPrimitive.content, row["pointer"]!!.jsonPrimitive.content), row["payload"]!!) }
            catch (_: LearningProtocolException) { valid = false }
            assertEquals(row["label"]!!.jsonPrimitive.content, row["valid"]!!.jsonPrimitive.boolean, valid)
            if (valid) positive++ else negative++
        }
        assertEquals(16, positive); assertEquals(138, negative)
    }

    @Test fun dailyBoundaryAndPracticeTimeUnionsRoundTripWithoutInventedFields() {
        for (index in listOf(0, 1)) {
            val value = example("DailyPreferenceRequest", index)
            assertEquals(value, featureJson.encodeToJsonElement(WireProgressDailyPreferenceRequest.serializer(),
                featureJson.decodeFromJsonElement(WireProgressDailyPreferenceRequest.serializer(), value)))
        }
        for (item in cases()) {
            val row = item.jsonObject
            if (row["valid"] != JsonPrimitive(true) || !row["pointer"]!!.jsonPrimitive.content.endsWith("/PracticeTimeRequest")) continue
            val value = row["payload"]!!
            assertEquals(value, featureJson.encodeToJsonElement(WireProgressPracticeTimeRequest.serializer(),
                featureJson.decodeFromJsonElement(WireProgressPracticeTimeRequest.serializer(), value)))
        }
    }

    @Test fun epochIndependentDailyChoiceUsesItsOriginalProfileAndReceivesCommonUnavailableError() = runBlocking {
        val request = featureJson.decodeFromJsonElement(WireProgressDailyPreferenceRequest.serializer(), example("DailyPreferenceRequest"))
        val scope = scope(request.profileContext.profileId)
        val error = buildJsonObject {
            put("schema_version", "5"); put("operation_id", request.operationId)
            putJsonObject("error") { put("code", "UPSTREAM_UNAVAILABLE"); put("retryable", true); put("message_ko", "잠시 후 다시 시도해 주세요.") }
        }
        val transport = Transport { response(error, 503) }
        val api = LearningProgressApi(client(scope, transport))
        assertEquals(LearningReply.Failed("UPSTREAM_UNAVAILABLE", true), api.setDailyListeningPreference(scope, request))
        assertEquals(request.operationId, transport.lastHeaders["Idempotency-Key"])
        assertEquals(1, transport.calls)
    }

    @Test fun invalidVersionsEnumsAndCalendarDatesNeverLeaveDevice() = runBlocking {
        val original = example("DailyPreferenceRequest").jsonObject
        val scope = scope(original["profile_context"]!!.jsonObject["profile_id"]!!.jsonPrimitive.content)
        val transport = Transport { throw AssertionError("Invalid request left device") }
        val api = LearningProgressApi(client(scope, transport))
        val badChoice = JsonObject(original["choice"]!!.jsonObject + ("decision" to JsonPrimitive("automatic")))
        val choice = original["choice"]!!.jsonObject
        val badBoundary = JsonObject(choice["boundary"]!!.jsonObject + ("selected_local_date" to JsonPrimitive("2026-02-30")))
        for (value in listOf(JsonObject(original + ("feature_contract_version" to JsonPrimitive("2"))),
            JsonObject(original + ("choice" to badChoice)),
            JsonObject(original + ("choice" to JsonObject(choice + ("boundary" to badBoundary)))))) {
            val request = featureJson.decodeFromJsonElement(WireProgressDailyPreferenceRequest.serializer(), value)
            assertEquals(LearningReply.Failed("INVALID_CONTRACT", false), api.setDailyListeningPreference(scope, request))
        }
        assertEquals(0, transport.calls)
    }

    @Test fun crossProfilePreferenceAndLateSessionResponsesAreRejected() = runBlocking {
        val value = example("LearningPreferenceView")
        val scope = scope(value.jsonObject["profile_id"]!!.jsonPrimitive.content)
        val foreign = JsonObject(value.jsonObject + ("profile_id" to JsonPrimitive(UUID.randomUUID().toString())))
        assertEquals(LearningReply.Failed("INVALID_CONTRACT", false), AiLearningApi(client(scope, Transport { response(foreign) })).readLearningPreference(scope))
        var current = scope
        val transport = Transport { current = scope.copy(owner = scope.owner.copy(revision = 2)); response(value) }
        val api = AiLearningApi(LearningApi(LearningSessionSource { current }, contracts(), transport, featureJson))
        assertEquals(LearningReply.Failed("SESSION_CHANGED", false), api.readLearningPreference(scope))
    }

    @Test fun recommendationEpochAndRequiredNullableStateRemainExplicit() = runBlocking {
        val value = example("LearningRecommendationView")
        val profile = value.jsonObject["profile_context"]!!.jsonObject
        val scope = scope(profile["profile_id"]!!.jsonPrimitive.content)
        val transport = Transport { response(value) }
        val api = AiLearningApi(client(scope, transport))
        val result = api.readLearningRecommendation(scope, 0)
        assertTrue(result is LearningReply.Stored)
        val stored = (result as LearningReply.Stored).value
        assertEquals("insufficient_evidence", stored.status); assertNull(stored.recommendation)
        assertEquals(LearningReply.Failed("INVALID_CONTRACT", false), api.readLearningRecommendation(scope, 1))
        assertEquals(1, transport.calls)
        val badEpoch = JsonObject(value.jsonObject + ("profile_context" to JsonObject(profile + ("privacy_epoch" to JsonPrimitive(1)))))
        assertEquals(LearningReply.Failed("INVALID_CONTRACT", false), AiLearningApi(client(scope, Transport { response(badEpoch) })).readLearningRecommendation(scope, 0))
    }

    @Test fun externalParametersAndPageLimitsAreValidatedBeforeTransport() = runBlocking {
        val scope = scope(UUID.randomUUID().toString())
        val transport = Transport { throw AssertionError("Invalid query left device") }
        val api = LearningProgressApi(client(scope, transport))
        assertEquals(LearningReply.Failed("INVALID_CONTRACT", false), api.listSpeakingRecords(scope, 0, limit = 51))
        assertEquals(LearningReply.Failed("INVALID_CONTRACT", false), api.listSpeakingRecords(scope, 0, visibility = "all"))
        assertEquals(0, transport.calls)
    }
    @Test fun nestedRecommendationActionReceiptCannotImportAnOldEpoch() = runBlocking {
        val requestValue = example("LearningRecommendationActionRequest")
        val request = featureJson.decodeFromJsonElement(WireAiLearningLearningRecommendationActionRequest.serializer(), requestValue)
        val scope = scope(request.profileContext.profileId)
        val recommendation = example("LearningRecommendationView").jsonObject
        val wrongEpoch = JsonObject(recommendation + ("profile_context" to buildJsonObject {
            put("profile_id", scope.owner.profileId.toString()); put("privacy_epoch", 1)
        }))
        val receipt = buildJsonObject {
            put("schema_version", "5"); put("ai_learning_contract_version", "1"); put("operation_id", request.operationId)
            put("recommendation", wrongEpoch); put("preference", example("LearningPreferenceView"))
        }
        val api = AiLearningApi(client(scope, Transport { response(receipt) }))
        assertEquals(LearningReply.Failed("INVALID_CONTRACT", false), api.actOnLearningRecommendation(scope, request))
    }

}
