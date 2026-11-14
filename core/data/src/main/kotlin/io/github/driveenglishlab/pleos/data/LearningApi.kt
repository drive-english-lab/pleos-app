package io.github.driveenglishlab.pleos.data

import android.content.Context
import io.github.driveenglishlab.pleos.domain.*
import io.github.driveenglishlab.pleos.data.wire.WirePersonalSettingsPatch
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.*

internal data class LearningHttpResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray)
internal interface LearningHttpTransport {
    /** The actual immutable configured origin. Test transports must explicitly identify their synthetic origin. */
    val configuredOrigin:String? get()=null
    suspend fun exchange(method: String, path: String, query: Map<String, String>, headers: Map<String, String>, body: ByteArray?): LearningHttpResponse
}
private class LearningTransportUnavailable : Exception()

/** Fixed configured service origin; no redirects, HTTP cache, logging or server secrets. */
internal class KtorLearningTransport(origin: String, allowLocalHttp: Boolean = false) : LearningHttpTransport, AutoCloseable {
    private val origin = origin.trim().trimEnd('/').takeIf { it.isNotEmpty() }
    override val configuredOrigin:String? get()=origin
    private val client = HttpClient(OkHttp) { expectSuccess = false; followRedirects = false }
    init {
        this.origin?.let {
            val uri = URI(it)
            val local = allowLocalHttp && uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "10.0.2.2")
            require((uri.scheme == "https" || local) && uri.host != null && uri.userInfo == null && uri.path.isEmpty() && uri.query == null && uri.fragment == null)
        }
    }
    override suspend fun exchange(method: String, path: String, query: Map<String, String>, headers: Map<String, String>, body: ByteArray?): LearningHttpResponse {
        val base = origin ?: throw LearningTransportUnavailable()
        require((path.startsWith("/v1/") || path.startsWith("/v2/")) && !path.contains('?') && !path.contains('#'))
        val response = client.request(base + path) {
            this.method = HttpMethod.parse(method.uppercase(java.util.Locale.ROOT))
            url { query.forEach { (name, value) ->
                // Internal map represents an array as JSON; OpenAPI form/explode uses repeated keys.
                if (name == "topics" && path == "/v2/content/catalog") {
                    val topics = learningJson.parseToJsonElement(value).jsonArray
                    require(topics.all { it is JsonPrimitive && it.isString })
                    parameters.appendAll(name, topics.map { it.jsonPrimitive.content })
                } else parameters.append(name, value)
            } }
            headers { headers.forEach { (name, value) -> append(name, value) } }
            if (body != null) setBody(body)
        }
        val channel = response.bodyAsChannel(); val bytes = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (!channel.isClosedForRead) {
            val count = channel.readAvailable(buffer, 0, buffer.size)
            if (count < 0) break
            if (bytes.size() + count > 1024 * 1024) throw LearningProtocolException()
            bytes.write(buffer, 0, count)
        }
        return LearningHttpResponse(response.status.value, response.headers.entries().associate { it.key to it.value.joinToString(",") }, bytes.toByteArray())
    }
    override fun close() { client.close() }
}

/** Every success is data tagged with its original scope, never an output/playback grant.
 * Retry uses the same caller-supplied IDs and immutable payload. This client never
 * changes epoch, creates a guest, regenerates feedback or retries behind the user.
 */
class LearningApi internal constructor(private val sessions: LearningSessionSource,
    private val contracts: LearningContractJson, private val transport: LearningHttpTransport,
    private val json: Json = learningJson) {
    constructor(context: Context, origin: String, sessions: LearningSessionSource, allowLocalHttp: Boolean = false) :
        this(sessions, LearningContractJson(context.applicationContext), KtorLearningTransport(origin, allowLocalHttp))
    private val observationTransportIdentity=NextObservationTransportIdentity(transport.configuredOrigin)

    fun incremental(context: Context): IncrementalLearningApi =
        IncrementalLearningApi(sessions, IncrementalContractJson(context.applicationContext), transport,observationTransportIdentity)

    internal fun validateSettings(request: WirePersonalSettingsPatch) {
        contracts.validate("personal-data.openapi.json", contracts.schema("personal-data.openapi.json", "#/components/schemas/SettingsPatch"),
            json.encodeToJsonElement(WirePersonalSettingsPatch.serializer(), request))
    }

    private fun sameSession(a: LearningCallScope?, b: LearningCallScope): Boolean = a != null &&
        a.owner.profileId == b.owner.profileId && a.owner.privacyEpoch == b.owner.privacyEpoch && a.owner.revision == b.owner.revision &&
        a.session.subjectId == b.session.subjectId && a.session.sessionId == b.session.sessionId && a.session.installationId == b.session.installationId

    internal suspend fun <T> call(file: String, template: String, method: String, scope: LearningCallScope, payload: JsonElement?,
        responseType: DeserializationStrategy<T>, pathArguments: Map<String, String>, queryArguments: Map<String, String?>): LearningReply<T> {
        if (!sameSession(sessions.current(), scope)) return LearningReply.Failed("SESSION_CHANGED", false)
        try {
            val operation = contracts.operation(file, template, method)
            val content = payload?.jsonObject
            val bodySchema = operation["requestBody"]?.jsonObject?.get("content")?.jsonObject?.get("application/json")?.jsonObject?.get("schema")?.jsonObject
            if (bodySchema != null) contracts.validate(file, bodySchema, payload ?: throw LearningProtocolException())
            else if (payload != null) throw LearningProtocolException()
            val context = content?.get("client_context")?.jsonObject ?: content?.get("profile_context")?.jsonObject
            if (context != null) {
                if (context["profile_id"]?.jsonPrimitive?.content != scope.owner.profileId.toString() ||
                    ("privacy_epoch" in context && template != "/v1/profile/preferences" && context["privacy_epoch"]?.jsonPrimitive?.long != scope.owner.privacyEpoch)) throw LearningProtocolException()
                if (template == "/v1/profile/preferences" && context["privacy_epoch"]!!.jsonPrimitive.long > scope.owner.privacyEpoch) throw LearningProtocolException()
                if ("presentation_token" in context && template != "/v1/practice-presentations/{presentation_id}/observations" && (context["presentation_token"]?.jsonPrimitive?.content != scope.owner.presentationToken.toString() ||
                    context["context_revision"]?.jsonPrimitive?.long != scope.owner.revision)) throw LearningProtocolException()
            }
            content?.get("profile_id")?.let { if (it.jsonPrimitive.content != scope.owner.profileId.toString()) throw LearningProtocolException() }
            val query = queryArguments.filterValues { it != null }.mapValues { it.value!! }
            if (file in featureApiDocuments && query["client_privacy_epoch"]?.toLong() != null &&
                query["client_privacy_epoch"]?.toLong() != scope.owner.privacyEpoch) throw LearningProtocolException()
            var path = template
            for ((name, value) in pathArguments) path = path.replace("{$name}", value)
            if (path.contains('{') || path.contains('}')) throw LearningProtocolException()
            for (original in operation["parameters"]?.jsonArray ?: JsonArray(emptyList())) {
                val (parameterFile, parameter) = contracts.dereference(file, original.jsonObject)
                val location = parameter["in"]!!.jsonPrimitive.content
                if (location == "header") continue
                val name = parameter["name"]!!.jsonPrimitive.content
                val value = if (location == "path") pathArguments[name] else query[name]
                if (value == null) {
                    if (parameter["required"] == JsonPrimitive(true)) throw LearningProtocolException()
                    continue
                }
                val schema = parameter["schema"]!!.jsonObject
                val encoded = if (schema["type"] == JsonPrimitive("integer")) JsonPrimitive(value.toLongOrNull() ?: throw LearningProtocolException()) else JsonPrimitive(value)
                contracts.validate(parameterFile, schema, encoded)
            }
            val body = payload?.toString()?.toByteArray(Charsets.UTF_8)
            if (body != null && body.size > if (template == "/v1/profile/sync") 256 * 1024 else 16 * 1024) return LearningReply.Failed("REQUEST_TOO_LARGE", false)
            val headers = mutableMapOf("Authorization" to scope.session.authorizationHeader(),
                "X-Installation-ID" to scope.session.installationId.toString(), "Accept" to "application/json", "Cache-Control" to "no-store")
            if (body != null) {
                headers["Content-Type"] = "application/json; charset=utf-8"
                headers["Idempotency-Key"] = (if (template == "/v1/attempt-feedback") content!!["attempt_id"] else content!!["operation_id"])!!.jsonPrimitive.content
            }
            val budget = if (content?.get("interaction_channel") == JsonPrimitive("gleo")) 4_000L else 8_000L
            val response = withTimeoutOrNull(budget) { transport.exchange(method, path, query, headers, body) }
                ?: return LearningReply.Failed("DEADLINE_EXCEEDED", true)
            if (!sameSession(sessions.current(), scope)) return LearningReply.Failed("SESSION_CHANGED", false)
            if (response.body.size > 1024 * 1024) throw LearningProtocolException()
            val mediaType = response.headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value?.substringBefore(';')?.trim()
            if (mediaType != "application/json") throw LearningProtocolException()
            val source = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(response.body)).toString()
            val parsed = contracts.parse(source)
            if (response.status == 200) {
                val schema = operation["responses"]!!.jsonObject["200"]!!.jsonObject["content"]!!.jsonObject["application/json"]!!.jsonObject["schema"]!!.jsonObject
                contracts.validate(file, schema, parsed)
                val result = parsed.jsonObject
                content?.get("operation_id")?.let { if (result["operation_id"] != null && result["operation_id"] != it) throw LearningProtocolException() }
                for (field in listOf("client_context", "content_ref")) {
                    content?.get(field)?.let { if (result[field] != null && result[field] != it) throw LearningProtocolException() }
                }
                result["profile_context"]?.jsonObject?.let { returned ->
                    if (template != "/v1/profile/bootstrap") {
                        if (returned["profile_id"]?.jsonPrimitive?.content != scope.owner.profileId.toString()) throw LearningProtocolException()
                        val epoch = returned["privacy_epoch"]?.jsonPrimitive?.long
                        if (epoch != null) when (template) {
                            "/v1/profile", "/v1/profile/preferences" -> if (epoch < scope.owner.privacyEpoch) throw LearningProtocolException()
                            "/v1/profile/history-deletions" -> if (epoch != scope.owner.privacyEpoch + 1 ||
                                result["previous_privacy_epoch"]?.jsonPrimitive?.long != scope.owner.privacyEpoch) throw LearningProtocolException()
                            else -> if (epoch != scope.owner.privacyEpoch) throw LearningProtocolException()
                        }
                    }
                }
                for (owner in listOfNotNull(result["profile_id"], result["preference"]?.jsonObject?.get("profile_id"))) {
                    if (owner.jsonPrimitive.content != scope.owner.profileId.toString()) throw LearningProtocolException()
                }
                if (file in featureApiDocuments) {
                    // Action receipts nest the recommendation view; it has its own learning epoch.
                    result["recommendation"]?.takeIf { it is JsonObject }?.jsonObject?.get("profile_context")?.jsonObject?.let { nested ->
                        if (nested["profile_id"]?.jsonPrimitive?.content != scope.owner.profileId.toString() ||
                            nested["privacy_epoch"]?.jsonPrimitive?.long != scope.owner.privacyEpoch) throw LearningProtocolException()
                    }
                    for ((key, expected) in pathArguments) {
                        val actual = result[key] ?: result["record"]?.jsonObject?.get(key)
                        if (actual != null && actual.jsonPrimitive.content != expected) throw LearningProtocolException()
                    }
                    for (key in listOf("article_id", "source_snapshot_id", "listening_mode", "target_band")) {
                        if (key in query && result[key]?.jsonPrimitive?.content != query[key]) throw LearningProtocolException()
                    }
                }
                if (template == "/v1/attempts/{attempt_id}" && result["attempt_id"]?.jsonPrimitive?.content != pathArguments["attempt_id"]) throw LearningProtocolException()
                return LearningReply.Stored(scope, json.decodeFromJsonElement(responseType, parsed))
            }
            val schema = contracts.schema("personal-data.openapi.json", "#/components/schemas/ErrorEnvelope")
            contracts.validate("personal-data.openapi.json", schema, parsed)
            val error = parsed.jsonObject["error"]!!.jsonObject
            val retryAfter = response.headers.entries.firstOrNull { it.key.equals("Retry-After", true) }?.value?.toLongOrNull()?.takeIf { it >= 0 }
            return LearningReply.Failed(error["code"]!!.jsonPrimitive.content, error["retryable"]!!.jsonPrimitive.boolean, retryAfter)
        } catch (error: CancellationException) { throw error }
        catch (_: LearningTransportUnavailable) { return LearningReply.Failed("RUNTIME_NOT_CONFIGURED", true) }
        catch (_: IllegalArgumentException) { return LearningReply.Failed("INVALID_CONTRACT", false) }
        catch (_: Exception) { return LearningReply.Failed("UPSTREAM_UNAVAILABLE", true) }
    }
}
