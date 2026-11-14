package io.github.driveenglishlab.pleos.data

import io.github.driveenglishlab.pleos.domain.*
import io.github.driveenglishlab.pleos.data.wire.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.*

/** Uses the existing authenticated transport/origin. A stored response never grants output. */
class IncrementalLearningApi internal constructor(
    private val sessions: LearningSessionSource,
    private val codecs: IncrementalContractJson,
    private val transport: LearningHttpTransport,
) {
    suspend fun isCurrent(scope: LearningCallScope): Boolean {
        val now = sessions.current() ?: return false
        return now.owner == scope.owner && now.session.subjectId == scope.session.subjectId &&
            now.session.sessionId == scope.session.sessionId && now.session.installationId == scope.session.installationId
    }

    fun <T> payload(bundle: String, name: String, value: T, serializer: SerializationStrategy<T>): JsonElement =
        codecs.parse(bundle, codecs.encode(bundle, name, value, serializer))

    suspend fun <T> call(bundle: String, template: String, method: String, scope: LearningCallScope,
        payload: JsonElement?, responseType: DeserializationStrategy<T>,
        pathArguments: Map<String, String> = emptyMap(), queryArguments: Map<String, String?> = emptyMap()): LearningReply<T> {
        if (!isCurrent(scope)) return LearningReply.Failed("SESSION_CHANGED", false)
        try {
            val file = "$bundle.openapi.json"
            val operation = codecs.operation(bundle, template, method.lowercase())
            val bodySchema = operation["requestBody"]?.jsonObject?.get("content")?.jsonObject?.get("application/json")?.jsonObject?.get("schema")?.jsonObject
            if (bodySchema != null) codecs.validate(bundle, file, bodySchema, payload ?: throw LearningProtocolException())
            else if (payload != null) throw LearningProtocolException()
            requireOwner(payload, scope)
            val query = queryArguments.filterValues { it != null }.mapValues { it.value!! }
            for (key in listOf("privacy_epoch", "learning_epoch", "client_privacy_epoch"))
                query[key]?.let { require(it.toLong() == scope.owner.privacyEpoch) }
            query["profile_id"]?.let { require(it == scope.owner.profileId.toString()) }
            val knownPath = mutableSetOf<String>(); val knownQuery = mutableSetOf<String>()
            for (entry in operation["parameters"]?.jsonArray ?: JsonArray(emptyList())) {
                val (parameterFile, parameter) = codecs.dereference(bundle, file, entry.jsonObject)
                val location = parameter["in"]!!.jsonPrimitive.content
                if (location == "header") continue
                val name = parameter["name"]!!.jsonPrimitive.content
                if (location == "path") knownPath += name else knownQuery += name
                val value = if (location == "path") pathArguments[name] else query[name]
                if (value == null) { require(parameter["required"] != JsonPrimitive(true)); continue }
                val schema = parameter["schema"]!!.jsonObject
                val encoded = when (schema["type"]?.jsonPrimitive?.content) {
                    "integer" -> JsonPrimitive(value.toLong())
                    "array", "object" -> codecs.parse(bundle, value)
                    "boolean" -> JsonPrimitive(value.toBooleanStrict())
                    else -> if (value == "null" && schema["anyOf"] != null) JsonNull else JsonPrimitive(value)
                }
                codecs.validate(bundle, parameterFile, schema, encoded)
            }
            require(pathArguments.keys.all { it in knownPath } && query.keys.all { it in knownQuery })
            var path = template
            pathArguments.forEach { (key, value) -> path = path.replace("{$key}", value) }
            require(!path.contains('{') && !path.contains('}') && !path.contains('?') && !path.contains('#'))
            val body = payload?.toString()?.toByteArray(Charsets.UTF_8)
            require(body == null || body.size <= if (template.endsWith("/profile/sync")) 256 * 1024 else 16 * 1024)
            val headers = mutableMapOf("Authorization" to scope.session.authorizationHeader(), "X-Installation-ID" to scope.session.installationId.toString(),
                "Accept" to "application/json", "Cache-Control" to "no-store")
            val input = payload as? JsonObject
            if (body != null) {
                headers["Content-Type"] = "application/json; charset=utf-8"
                val identity = if(template.endsWith("/attempt-feedback")) input?.get("attempt_id") else
                    input?.get("operation_id") ?: input?.get("request_id") ?: input?.get("observation_id")
                headers["Idempotency-Key"] = identity?.jsonPrimitive?.content ?: throw LearningProtocolException()
            }
            val budget = if (input?.get("interaction_channel") == JsonPrimitive("gleo")) 4_000L else 8_000L
            val response = withTimeoutOrNull(budget) { transport.exchange(method, path, query, headers, body) }
                ?: return LearningReply.Failed("DEADLINE_EXCEEDED", true)
            if (!isCurrent(scope)) return LearningReply.Failed("SESSION_CHANGED", false)
            require(response.body.size <= 1024 * 1024)
            require(response.headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value?.substringBefore(';')?.trim() == "application/json")
            val source = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(response.body)).toString()
            val parsed = codecs.parse(bundle, source)
            val status = response.status.toString()
            val schema = operation["responses"]?.jsonObject?.get(status)?.jsonObject?.get("content")?.jsonObject?.get("application/json")?.jsonObject?.get("schema")?.jsonObject
                ?: throw LearningProtocolException()
            codecs.validate(bundle, file, schema, parsed)
            if (response.status != 200) {
                val error = parsed.jsonObject["error"]!!.jsonObject
                val retryAfter = response.headers.entries.firstOrNull { it.key.equals("Retry-After", true) }?.value?.toLongOrNull()?.takeIf { it >= 0 }
                return LearningReply.Failed(error["code"]!!.jsonPrimitive.content, error["retryable"]!!.jsonPrimitive.boolean, retryAfter)
            }
            requireOwner(parsed, scope)
            val result = parsed.jsonObject
            for (key in listOf("operation_id", "attempt_id", "request_id", "observation_id"))
                input?.get(key)?.let { expected -> result[key]?.let { require(it == expected) } }
            for (key in listOf("content_ref", "source_ref", "client_context"))
                input?.get(key)?.let { expected -> result[key]?.let { require(it == expected) } }
            for ((key, expected) in pathArguments) result[key]?.let { require(it.jsonPrimitive.content == expected) }
            val responseRef = schema["\$ref"]!!.jsonPrimitive.content.substringAfterLast('/')
            val value = codecs.decode(bundle, responseRef, source, responseType)
            return LearningReply.Stored(scope, value)
        } catch (error: CancellationException) { throw error }
        catch (_: IllegalArgumentException) { return LearningReply.Failed("INVALID_CONTRACT", false) }
        catch (_: Exception) { return LearningReply.Failed("UPSTREAM_UNAVAILABLE", true) }
    }

    private fun requireOwner(element: JsonElement?, scope: LearningCallScope) {
        when (element) {
            is JsonObject -> {
                element["profile_id"]?.let { require(it.jsonPrimitive.content == scope.owner.profileId.toString()) }
                for (key in listOf("privacy_epoch", "learning_epoch")) element[key]?.let { require(it.jsonPrimitive.long == scope.owner.privacyEpoch) }
                // Presentation tokens identify the original observer. They are not authorization;
                // session/owner matching above still rejects a late session result.
                element.values.forEach { requireOwner(it, scope) }
            }
            is JsonArray -> element.forEach { requireOwner(it, scope) }
            else -> Unit
        }
    }

    suspend fun context(scope: LearningCallScope, selectionId: String) = call("review", "/v1/review/listening-contexts/{selection_id}", "get", scope,
        null, WireReviewListeningContextResponse.serializer(), mapOf("selection_id" to selectionId), mapOf("review_contract_version" to "1"))
    suspend fun sentenceManifest(scope:LearningCallScope,scriptId:String,query:WireReviewSentenceManifestQuery)=call("review",
        "/v1/content/sentence-manifests/{script_id}","get",scope,null,WireReviewSentenceManifestResponse.serializer(),mapOf("script_id" to scriptId),
        mapOf("source_kind" to query.sourceKind,"bundle_id" to (query.bundleId?:"null"),"material_generation_id" to (query.materialGenerationId?:"null"),
            "segments_version" to query.segmentsVersion.toString(),"script_text_hash" to query.scriptTextHash))
    suspend fun catalog(scope:LearningCallScope,topics:List<String>,limit:Long=20,cursor:String?=null)=call("preparation","/v2/content/catalog","get",scope,
        null,WirePreparationCatalogResponse.serializer(),queryArguments=mapOf("topics" to JsonArray(topics.map(::JsonPrimitive)).toString(),"limit" to limit.toString(),"cursor" to cursor))
    suspend fun command(scope: LearningCallScope, selectionId: String, request: WireReviewReviewCommandRequest) = call("review",
        "/v1/review/listening-contexts/{selection_id}/commands", "post", scope, payload("review", "ReviewCommandRequest", request, WireReviewReviewCommandRequest.serializer()),
        WireReviewReviewCommandResponse.serializer(), mapOf("selection_id" to selectionId))
    suspend fun replay(scope: LearningCallScope, request: WireReviewHistoryReplayRequest) = call("review", "/v1/review/history-replays", "post", scope,
        payload("review", "HistoryReplayRequest", request, WireReviewHistoryReplayRequest.serializer()), WireReviewHistoryReplayResponse.serializer())
    suspend fun observe(scope: LearningCallScope, request: WireReviewSentenceObservationRequest) = call("review", "/v1/review/sentence-observations", "post", scope,
        payload("review", "SentenceObservationRequest", request, WireReviewSentenceObservationRequest.serializer()), WireReviewSentenceObservationResponse.serializer())
    suspend fun fence(scope: LearningCallScope, request: WireReviewAssistanceFenceRequest) = call("review", "/v1/review/attempt-assistance-fences", "post", scope,
        payload("review", "AssistanceFenceRequest", request, WireReviewAssistanceFenceRequest.serializer()), WireReviewAssistanceFenceResponse.serializer())
    suspend fun prepare(scope: LearningCallScope, request: WirePreparationPreparationRequest) = call("preparation", "/v2/content/preparations", "post", scope,
        payload("preparation", "PreparationRequest", request, WirePreparationPreparationRequest.serializer()), WirePreparationPreparationResponse.serializer())
    suspend fun preparation(scope: LearningCallScope, requestId: String) = call("preparation", "/v2/content/preparations/{request_id}", "get", scope,
        null, WirePreparationPreparationResponse.serializer(), mapOf("request_id" to requestId), mapOf("preparation_contract_version" to "1",
            "profile_id" to scope.owner.profileId.toString(), "privacy_epoch" to scope.owner.privacyEpoch.toString()))
    suspend fun retry(scope: LearningCallScope, requestId: String, request: WirePreparationPreparationRetryRequest) = call("preparation", "/v2/content/preparations/{request_id}/retry", "post", scope,
        payload("preparation", "PreparationRetryRequest", request, WirePreparationPreparationRetryRequest.serializer()), WirePreparationPreparationRetryResponse.serializer(), mapOf("request_id" to requestId))
}
