package io.github.driveenglishlab.pleos.platform.media

import java.util.Locale

/** Content is supplied by the application, never accepted as an external playback URI. */
data class NewsArticle(val id: String, val title: String, val summary: String, val createdAt: String, val uri: String, val durationMs: Long) {
    init { require(id.isNotBlank() && title.isNotBlank() && uri.isNotBlank() && durationMs > 0) }
}
interface NewsMediaCatalog {
    val articles: List<NewsArticle>
    fun find(id: String): NewsArticle? = articles.firstOrNull { it.id == id }
    fun previous(id: String): NewsArticle? = articles.indexOfFirst { it.id == id }
        .let { index -> if (index > 0) articles[index - 1] else null }
    fun next(id: String): NewsArticle? = articles.indexOfFirst { it.id == id }
        .let { index -> if (index >= 0) articles.getOrNull(index + 1) else null }
    fun search(query: String): List<NewsArticle> {
        val text = query.trim().lowercase(Locale.ROOT)
        return if (text.isEmpty()) emptyList() else articles.filter { it.title.lowercase(Locale.ROOT).contains(text) }
    }
    object Empty : NewsMediaCatalog { override val articles = emptyList<NewsArticle>() }
}
