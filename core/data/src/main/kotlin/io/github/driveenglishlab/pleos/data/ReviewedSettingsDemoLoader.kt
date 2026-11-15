package io.github.driveenglishlab.pleos.data

import android.content.Context
import io.github.driveenglishlab.pleos.domain.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** A hash is identity, never approval. The trusted review input is supplied separately from assets. */
class ReviewedSettingsDemoLoader internal constructor(
    private val readManifest: () -> ByteArray?,
    private val strictParse: (String) -> JsonElement,
    private val trustedApproval: () -> DemoApproval?,
    private val nowEpochMs: () -> Long,
) {
    constructor(
        context: Context,
        trustedApproval: () -> DemoApproval? = { null },
        nowEpochMs: () -> Long = System::currentTimeMillis,
    ) : this(
        readManifest = {
            context.assets.open(MANIFEST_PATH).use { input ->
                val result = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(result.size() + count <= MAX_BYTES)
                    result.write(buffer, 0, count)
                }
                result.toByteArray()
            }
        },
        strictParse = LearningContractJson(context)::parse,
        trustedApproval = trustedApproval,
        nowEpochMs = nowEpochMs,
    )

    /** No personal API, saved selection, output or default synthetic content is created here. */
    suspend fun load(): SettingsDemoManifest? = withContext(Dispatchers.IO) {
        try {
            val approval = trustedApproval() ?: return@withContext null
            if (!approval.valid(approval.reviewedManifestHash, nowEpochMs())) return@withContext null
            val bytes = readManifest() ?: return@withContext null
            require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            require(approval.valid(hash, nowEpochMs()))
            val source = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val root = strictParse(source).closed("schema_version", "demo_id", "version", "source", "original_full", "briefing", "followups", "questions")
            require(root.integer("schema_version") == 1L)
            val origin = root.getValue("source").closed("publisher", "original_url", "published_at_raw", "published_at_utc", "synthetic")
            val originalUrl = origin.nullableString("original_url")
            originalUrl?.let {
                val uri = java.net.URI(it)
                require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null)
            }
            val utc = origin.nullableString("published_at_utc")
            utc?.let { require(OffsetDateTime.parse(it).offset.totalSeconds == 0) }
            val followups = root.getValue("followups").closed(*DemoFollowupRole.entries.map { it.name.lowercase() }.toTypedArray())
            val questions = root.getValue("questions").closed(*LearningBand.entries.map { it.name.lowercase() }.toTypedArray())
            val result = SettingsDemoManifest(
                root.uuid("demo_id"), root.integer("version"), hash,
                DemoSource(origin.string("publisher"), originalUrl, origin.nullableString("published_at_raw"), utc, origin.boolean("synthetic")),
                excerpt(root.getValue("original_full")), excerpt(root.getValue("briefing")),
                DemoFollowupRole.entries.associateWith { excerpt(followups.getValue(it.name.lowercase())) },
                LearningBand.entries.associateWith { excerpt(questions.getValue(it.name.lowercase())) }, approval,
            )
            // Each choice must resolve to the exact reviewed excerpts, including the omitted
            // briefing + English-summary followup. No band-specific original is accepted.
            for (mode in ListeningMode.entries) for (followup in Followup.entries) for (band in LearningBand.entries) {
                require(SettingsPreviewPolicy.plan(result, SettingsPreviewChoice(mode, followup, band), nowEpochMs()) != null)
            }
            if (trustedApproval() != approval || !result.approved(nowEpochMs())) null else result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Do not put the original text, review references or parse exceptions in logs/UI.
            null
        }
    }

    private fun excerpt(value: JsonElement): DemoExcerpt {
        val row = value.closed("text", "text_hash", "language", "segments")
        val manifest = row.getValue("segments").closed("manifest_id", "script_id", "script_hash", "segments_version", "manifest_hash", "language", "segments")
        val items = manifest.getValue("segments") as? JsonArray ?: error("Invalid demo segments")
        require(items.size in 1..512)
        val version = manifest.integer("segments_version")
        require(version in 1..Int.MAX_VALUE.toLong())
        val segments = items.map {
            val item = it.closed("segment_id", "order", "sentence_id", "start_cp", "end_cp", "text_hash")
            fun index(key: String): Int {
                val number = item.integer(key)
                require(number in 0..Int.MAX_VALUE.toLong())
                return number.toInt()
            }
            SpeechSegment(item.uuid("segment_id"), index("order"), item.uuid("sentence_id"), index("start_cp"), index("end_cp"), item.string("text_hash"))
        }
        return DemoExcerpt(row.string("text"), row.string("text_hash"), row.language(), SegmentManifest(
            manifest.uuid("manifest_id"), manifest.uuid("script_id"), manifest.string("script_hash"), version.toInt(),
            manifest.string("manifest_hash"), manifest.language(), segments,
        ))
    }

    private fun JsonElement.closed(vararg keys: String): JsonObject = (this as? JsonObject)?.also { require(it.keys == keys.toSet()) }
        ?: error("Invalid demo object")
    private fun JsonObject.primitive(key: String): JsonPrimitive = getValue(key) as? JsonPrimitive ?: error("Invalid demo field")
    private fun JsonObject.string(key: String): String = primitive(key).also { require(it.isString && it.content.isNotBlank()) }.content
    private fun JsonObject.nullableString(key: String): String? = if (getValue(key) == JsonNull) null else string(key)
    private fun JsonObject.integer(key: String): Long = primitive(key).also { require(!it.isString) }.longOrNull ?: error("Invalid demo integer")
    private fun JsonObject.boolean(key: String): Boolean = primitive(key).also { require(!it.isString) }.booleanOrNull ?: error("Invalid demo boolean")
    private fun JsonObject.uuid(key: String): UUID = string(key).let { text -> UUID.fromString(text).also { require(text == it.toString()) } }
    private fun JsonObject.language(): SegmentLanguage = when (string("language")) { "en" -> SegmentLanguage.EN; "ko" -> SegmentLanguage.KO; else -> error("Invalid demo language") }

    companion object {
        const val MANIFEST_PATH = "review-demo/manifest.json"
        private const val MAX_BYTES = 512 * 1024
    }
}
