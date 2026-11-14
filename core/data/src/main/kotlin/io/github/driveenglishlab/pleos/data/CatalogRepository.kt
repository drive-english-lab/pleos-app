package io.github.driveenglishlab.pleos.data

import android.content.Context
import io.github.driveenglishlab.pleos.data.wire.WirePersonalAudioAccessRequest
import io.github.driveenglishlab.pleos.data.wire.WirePersonalAudioAccessResponse
import io.github.driveenglishlab.pleos.domain.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

internal interface CatalogHttpTransport {
    suspend fun get(table: String, query: Map<String, String>): LearningHttpResponse
    suspend fun getAuthenticated(table:String,query:Map<String,String>,authorization:String):LearningHttpResponse = throw CatalogNotConfigured()
}
private class CatalogNotConfigured : Exception()
private class CatalogContentUnavailable : Exception()
private class CatalogHttpFailure(val retryable: Boolean) : Exception()

/** Publishable-key GET only. Personal bindings additionally require the live user's JWT. */
internal class PublicCatalogTransport(origin: String, private val publishableKey: String,
    allowLocalHttp: Boolean = false) : CatalogHttpTransport {
    private val origin = origin.trim().trimEnd('/').takeIf(String::isNotEmpty)
    private val client = HttpClient(OkHttp) { expectSuccess = false; followRedirects = false }
    init {
        this.origin?.let {
            val uri = URI(it)
            val local = allowLocalHttp && uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "10.0.2.2")
            require((uri.scheme == "https" || local) && uri.host != null && uri.userInfo == null && uri.path.isEmpty() && uri.query == null && uri.fragment == null)
            require(publishableKey.matches(Regex("sb_publishable_[A-Za-z0-9_-]+"))) { "A publishable key is required." }
        }
    }
    override suspend fun get(table: String, query: Map<String, String>): LearningHttpResponse {
        require(table in setOf("news_catalog", "news_bundles", "news_scripts", "news_practice_tasks", "news_material_components"))
        return request(table,query,null)
    }
    override suspend fun getAuthenticated(table:String,query:Map<String,String>,authorization:String):LearningHttpResponse {
        require(table=="profile_material_bindings" && authorization.startsWith("Bearer ") && !authorization.contains('\r') && !authorization.contains('\n'))
        return request(table,query,authorization)
    }
    private suspend fun request(table:String,query:Map<String,String>,authorization:String?):LearningHttpResponse {
        val base = origin ?: throw CatalogNotConfigured()
        val response = client.get("$base/rest/v1/$table") {
            url { query.forEach { (key, value) -> parameters.append(key, value) } }
            headers { append("apikey", publishableKey); append("Accept", "application/json"); append("Cache-Control", "no-store")
                if(authorization!=null)append("Authorization",authorization) }
        }
        val channel = response.bodyAsChannel(); val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (!channel.isClosedForRead) {
            val count = channel.readAvailable(buffer, 0, buffer.size)
            if (count < 0) break
            require(output.size()+count <= 1024*1024)
            output.write(buffer, 0, count)
        }
        return LearningHttpResponse(response.status.value, response.headers.entries().associate { it.key to it.value.joinToString(",") }, output.toByteArray())
    }
}

class CatalogRepository internal constructor(private val contracts: LearningContractJson,
    private val transport: CatalogHttpTransport, private val supabaseOrigin: String) {
    constructor(context: Context, allowLocalHttp: Boolean = false) : this(LearningContractJson(context.applicationContext),
        PublicCatalogTransport(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY, allowLocalHttp), BuildConfig.SUPABASE_URL)

    /** Shares the existing publishable-key transport; no separate HTTP engine or server key. */
    fun prepared(context:Context,api:IncrementalLearningApi,dao:ReviewDao):PreparedCatalogRepository =
        PreparedCatalogRepository(IncrementalContractJson(context.applicationContext),this,api,dao)

    internal suspend fun publicRows(table:String,columns:List<String>,query:Map<String,String>):List<JsonObject> = rows(table,columns,query)
    internal suspend fun bindingRows(scope:LearningCallScope,columns:List<String>,query:Map<String,String>):List<JsonObject> =
        decodeRows(transport.getAuthenticated("profile_material_bindings",query+("select" to columns.joinToString(",")),scope.session.authorizationHeader()),columns)

    private val catalogColumns = listOf("article_id", "bundle_id", "title", "publisher", "original_url", "published_at_raw", "published_at_utc", "timezone_status",
        "topic_ids", "original_duration_ms", "briefing_duration_ms", "published_at", "access_until", "status")
    private val bundleColumns = listOf("bundle_id", "article_id", "source_snapshot_id", "schema_version", "status", "published_at", "access_until")
    private val scriptColumns = listOf("listening_script_id", "bundle_id", "role", "base_script_id", "language", "text", "text_hash", "audio_asset_id", "duration_ms")

    suspend fun catalogPage(offset: Int = 0, limit: Int = 20, articleId: UUID? = null): CatalogRead<List<CatalogArticle>> = read {
        require(offset >= 0 && limit in 1..50)
        rows("news_catalog", catalogColumns, mapOf("status" to "eq.published", "order" to "published_at.desc,article_id.asc", "offset" to "$offset", "limit" to "$limit") +
            (articleId?.let { mapOf("article_id" to "eq.$it") } ?: emptyMap()))
            .also { require(it.size <= limit) }.map { item ->
                val verified = when (item.text("timezone_status", 20)) { "verified" -> true; "unknown" -> false; else -> throw IllegalArgumentException() }
                val original = item["published_at_utc"]!!.let { if (it is JsonNull) null else timestamp(item.text("published_at_utc", 120)) }
                require(verified == (original != null) && item.text("status", 20) == "published")
                val topics = item["topic_ids"] as? JsonArray ?: throw IllegalArgumentException()
                val values = topics.map { value -> value.jsonPrimitive.also { require(it.isString && it.content.codePointCount(0, it.content.length) in 1..80) }.content }
                require(values.size in 1..20 && values.distinct().size == values.size)
                val published = timestamp(item.text("published_at", 120)); val until = timestamp(item.text("access_until", 120)); require(until > published)
                CatalogArticle(item.id("article_id"), item.id("bundle_id"), item.text("title", 500), item.text("publisher", 120),
                    sourceUri(item.text("original_url", 2048)), item.text("published_at_raw", 120), original, verified, values.toSet(),
                    item.positive("original_duration_ms"), item.positive("briefing_duration_ms"), published, until)
            }
    }

    suspend fun readMain(ref: ContentRef): CatalogRead<PublishedScript> = read {
        pinnedBundle(ref)
        val role = if (ref.listeningMode == ListeningMode.ORIGINAL_FULL) CatalogScriptRole.ORIGINAL_FULL else CatalogScriptRole.BRIEFING
        val row = single("news_scripts", scriptColumns, mapOf("bundle_id" to "eq.${ref.bundleId}", "listening_script_id" to "eq.${ref.listeningScriptId}"))
        parseScript(row).also { require(it.scriptId == ref.listeningScriptId && it.bundleId == ref.bundleId && it.role == role && it.baseScriptId == null) }
    }

    suspend fun readArticleRef(article: CatalogArticle, mode: ListeningMode): CatalogRead<ContentRef> = read {
        val bundle = single("news_bundles", bundleColumns, mapOf("bundle_id" to "eq.${article.bundleId}", "article_id" to "eq.${article.articleId}"))
        require(bundle.text("status", 20) == "published")
        val row = single("news_scripts", scriptColumns, mapOf("bundle_id" to "eq.${article.bundleId}", "role" to "eq.${if (mode == ListeningMode.ORIGINAL_FULL) "original_full" else "briefing"}"))
        val script = parseScript(row)
        ContentRef(article.articleId, article.bundleId, bundle.id("source_snapshot_id"), script.scriptId, mode)
    }

    suspend fun readPractice(ref: ContentRef): CatalogRead<PublishedPracticeTask> = read {
        pinnedBundle(ref)
        val columns = listOf("task_id", "bundle_id", "listening_script_id", "article_id", "basic_question_version_id", "question_en", "support_manifest")
        val row = single("news_practice_tasks", columns, mapOf("bundle_id" to "eq.${ref.bundleId}", "listening_script_id" to "eq.${ref.listeningScriptId}"))
        require(row.id("bundle_id") == ref.bundleId && row.id("listening_script_id") == ref.listeningScriptId && row.id("article_id") == ref.articleId)
        val value = row.getValue("support_manifest")
        contracts.validate("common.schema.json", contracts.schema("common.schema.json", "#/\$defs/PracticeSupportManifest"), value)
        val manifest = learningJson.decodeFromJsonElement(io.github.driveenglishlab.pleos.data.wire.WireCommonPracticeSupportManifest.serializer(), value).domain()
        val fixed = PracticeContentRef(ref, row.id("task_id"), row.id("basic_question_version_id"))
        require(manifest.taskId == fixed.taskId && manifest.questionRef == fixed.questionRef)
        PublishedPracticeTask(fixed, row.text("question_en", 500), manifest)
    }

    suspend fun readTitle(ref: ContentRef): CatalogRead<String> = read {
        pinnedBundle(ref)
        val row = single("news_bundles", listOf("bundle_id", "article_id", "source_snapshot_id", "public_payload"), mapOf("bundle_id" to "eq.${ref.bundleId}"))
        require(row.id("bundle_id") == ref.bundleId && row.id("article_id") == ref.articleId && row.id("source_snapshot_id") == ref.sourceSnapshotId)
        row.getValue("public_payload").jsonObject.getValue("source").jsonObject.text("title", 500)
    }

    suspend fun readFollowup(ref: ContentRef, role: CatalogScriptRole): CatalogRead<PublishedScript> = read {
        require(role.baseMode != null && role.baseMode == ref.listeningMode)
        pinnedBundle(ref)
        val row = single("news_scripts", scriptColumns, mapOf("bundle_id" to "eq.${ref.bundleId}", "base_script_id" to "eq.${ref.listeningScriptId}", "role" to "eq.${role.wire}"))
        parseScript(row).also { require(it.bundleId == ref.bundleId && it.role == role && it.baseScriptId == ref.listeningScriptId) }
    }

    suspend fun audioAccess(api: LearningApi, scope: LearningCallScope, script: PublishedScript, operationId: UUID): LearningReply<WirePersonalAudioAccessResponse> {
        val reply = api.issueAudioAccess(scope, WirePersonalAudioAccessRequest("5", operationId.toString(), script.bundleId.toString(), script.audioAssetId.toString()))
        if (reply is LearningReply.Stored) {
            try {
                val value = reply.value; val url = URI(value.signedUrl); val origin = URI(supabaseOrigin)
                require(value.bundleId == script.bundleId.toString() && value.audioAssetId == script.audioAssetId.toString() && value.durationMs == script.durationMs && value.assetHash.matches(Regex("[a-f0-9]{64}")))
                require(url.scheme == "https" && url.rawAuthority == origin.rawAuthority && url.userInfo == null && url.fragment == null &&
                    url.path.startsWith("/storage/v1/object/sign/") && url.rawQuery?.split('&')?.let { parts ->
                        parts.size == 1 && parts.single().startsWith("token=") && parts.single().length > 6
                    } == true)
            } catch (_: IllegalArgumentException) { return LearningReply.Failed("INVALID_CONTRACT", false) }
        }
        return reply
    }

    private suspend fun pinnedBundle(ref: ContentRef) {
        val row = single("news_bundles", bundleColumns, mapOf("bundle_id" to "eq.${ref.bundleId}"))
        require(row.id("bundle_id") == ref.bundleId && row.id("article_id") == ref.articleId && row.id("source_snapshot_id") == ref.sourceSnapshotId &&
            row.text("schema_version", 1) == "5" && row.text("status", 20) == "published")
        require(timestamp(row.text("access_until", 120)) > timestamp(row.text("published_at", 120)))
    }
    private fun parseScript(row: JsonObject): PublishedScript {
        val role = CatalogScriptRole.entries.singleOrNull { it.wire == row.text("role", 40) } ?: throw IllegalArgumentException()
        require(row.text("language", 2) == role.language)
        val base = row["base_script_id"]!!.let { if (it is JsonNull) null else row.id("base_script_id") }
        require((role.baseMode == null) == (base == null))
        val text = row.text("text", 200000); val hash = row.text("text_hash", 64)
        require(hash.matches(Regex("[a-f0-9]{64}")) && LearningJson.hash(text) == hash)
        return PublishedScript(row.id("listening_script_id"), row.id("bundle_id"), role, base, text, hash, row.id("audio_asset_id"), row.positive("duration_ms"))
    }
    private suspend fun single(table: String, columns: List<String>, query: Map<String, String>): JsonObject {
        val values = rows(table, columns, query + ("limit" to "2")); require(values.size <= 1)
        return values.singleOrNull() ?: throw CatalogContentUnavailable()
    }
    private suspend fun rows(table: String, columns: List<String>, query: Map<String, String>): List<JsonObject> {
        val response = transport.get(table, query + ("select" to columns.joinToString(",")))
        return decodeRows(response,columns)
    }
    private fun decodeRows(response:LearningHttpResponse,columns:List<String>):List<JsonObject> {
        if (response.status != 200) throw CatalogHttpFailure(response.status >= 500)
        require(response.body.size <= 1024*1024 && response.headers.entries.any { it.key.equals("Content-Type", true) && it.value.substringBefore(';').trim() == "application/json" })
        val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(response.body)).toString() }
        catch (_: java.nio.charset.CharacterCodingException) { throw IllegalArgumentException() }
        val value = contracts.parse(text); require(value is JsonArray)
        return value.map { item -> require(item is JsonObject && item.keys == columns.toSet()); item }
    }
    private suspend fun <T> read(block: suspend () -> T): CatalogRead<T> = try {
        withTimeoutOrNull(8000) { CatalogRead.Stored(block()) } ?: CatalogRead.Unavailable("DEADLINE_EXCEEDED", true)
    } catch (error: CancellationException) { throw error }
    catch (_: CatalogContentUnavailable) { CatalogRead.Unavailable("CONTENT_UNAVAILABLE", false) }
    catch (_: CatalogNotConfigured) { CatalogRead.Unavailable("RUNTIME_NOT_CONFIGURED", true) }
    catch (error: CatalogHttpFailure) { CatalogRead.Unavailable("PUBLIC_CONTENT_UNAVAILABLE", error.retryable) }
    catch (_: IllegalArgumentException) { CatalogRead.Unavailable("INVALID_CONTENT", false) }
    catch (_: java.time.DateTimeException) { CatalogRead.Unavailable("INVALID_CONTENT", false) }
    catch (_: Exception) { CatalogRead.Unavailable("UPSTREAM_UNAVAILABLE", true) }

    private fun JsonObject.text(key: String, max: Int): String = getValue(key).jsonPrimitive.also {
        require(it.isString && it.content.isNotBlank() && it.content.codePointCount(0, it.content.length) in 1..max)
    }.content
    private fun JsonObject.id(key: String): UUID = text(key, 36).let { value -> UUID.fromString(value).also { require(it.toString() == value.lowercase()) } }
    private fun JsonObject.positive(key: String): Long = getValue(key).jsonPrimitive.let { value -> require(!value.isString); requireNotNull(value.longOrNull).also { require(it >= 1) } }
    private fun timestamp(value: String): Instant = OffsetDateTime.parse(value).toInstant()
    private fun sourceUri(value: String): URI = try { URI(value).also { require(it.scheme?.lowercase() in setOf("https", "http") && it.host != null && it.userInfo == null) } }
        catch (_: java.net.URISyntaxException) { throw IllegalArgumentException() }
}
