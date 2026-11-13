package io.github.driveenglishlab.pleos.domain

import java.net.URI
import java.time.Instant
import java.util.UUID

data class CatalogArticle(val articleId: UUID, val bundleId: UUID, val title: String, val publisher: String,
    val originalUrl: URI, val publishedAtRaw: String, val originalPublishedAt: Instant?, val originalTimeVerified: Boolean,
    val topics: Set<String>, val originalDurationMs: Long, val briefingDurationMs: Long,
    val publishedAt: Instant, val accessUntil: Instant)

enum class CatalogScriptRole(val wire: String, val language: String, val baseMode: ListeningMode?) {
    ORIGINAL_FULL("original_full", "en", null), BRIEFING("briefing", "en", null),
    ORIGINAL_EN_SUMMARY("original_en_summary", "en", ListeningMode.ORIGINAL_FULL),
    ORIGINAL_KO_SUMMARY("original_ko_summary", "ko", ListeningMode.ORIGINAL_FULL),
    ORIGINAL_KO_TRANSLATION("original_ko_translation", "ko", ListeningMode.ORIGINAL_FULL),
    BRIEFING_KO_SUMMARY("briefing_ko_summary", "ko", ListeningMode.BRIEFING),
    BRIEFING_KO_TRANSLATION("briefing_ko_translation", "ko", ListeningMode.BRIEFING),
}

data class PublishedScript(val scriptId: UUID, val bundleId: UUID, val role: CatalogScriptRole, val baseScriptId: UUID?,
    val text: String, val textHash: String, val audioAssetId: UUID, val durationMs: Long) {
    override fun toString() = "PublishedScript(text=redacted)"
}

/** Public content data is neither an output permission nor an automatic playback instruction. */
sealed interface CatalogRead<out T> {
    class Stored<T>(val value: T) : CatalogRead<T> { override fun toString() = "CatalogRead.Stored(content=redacted)" }
    data class Unavailable(val code: String, val retryable: Boolean) : CatalogRead<Nothing>
}
