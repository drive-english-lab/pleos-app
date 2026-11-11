package io.github.driveenglishlab.pleos.domain

import java.util.Locale
import java.util.UUID

enum class ClearIntent { STOP, NEXT_NEWS, RESUME_NEWS, THINKING, HELP, ANSWER, CLARIFY }

data class ArticleIdentity(val articleId: UUID, val title: String, val voiceTitle: String,
                           val publisher: String, val publishedAtText: String)
sealed interface TitleResolution {
    data class Selected(val article: ArticleIdentity) : TitleResolution
    data class AskOnce(val candidates: List<ArticleIdentity>) : TitleResolution
    data object KeepCurrentWork : TitleResolution
}

sealed interface QuestionOrigin {
    data class Listening(val point: ReturnPoint) : QuestionOrigin
    data class Practice(val lessonId: UUID, val task: PracticeRef) : QuestionOrigin
    data class ArticleOffer(val content: ContentRef) : QuestionOrigin
}

sealed interface ConversationReturn {
    data class Listening(val point: ReturnPoint) : ConversationReturn
    data class SameQuestion(val lessonId: UUID, val task: PracticeRef) : ConversationReturn
    data class ReannounceOffer(val content: ContentRef) : ConversationReturn
}

object ConversationPolicy {
    /** Exact unambiguous commands only. Answer quotations containing next/stop remain answers. */
    fun classify(text: String): ClearIntent = when (text.trim().lowercase().trimEnd('.', '!', '?')) {
        "그만", "멈춰", "stop", "stop playback" -> ClearIntent.STOP
        "다음 뉴스", "next news", "play the next article" -> ClearIntent.NEXT_NEWS
        "뉴스 이어줘", "resume the news" -> ClearIntent.RESUME_NEWS
        "생각 중", "생각 중이야", "i'm thinking" -> ClearIntent.THINKING
        "힌트 줘", "내용을 모르겠어", "질문 해석", "예문 알려줘" -> ClearIntent.HELP
        "" -> ClearIntent.CLARIFY
        else -> ClearIntent.ANSWER
    }

    fun returnFromQuestion(point: ReturnPoint): ReturnPoint = point.afterConversation()

    fun returnTo(origin: QuestionOrigin): ConversationReturn = when (origin) {
        is QuestionOrigin.Listening -> ConversationReturn.Listening(origin.point.afterConversation())
        is QuestionOrigin.Practice -> ConversationReturn.SameQuestion(origin.lessonId, origin.task)
        is QuestionOrigin.ArticleOffer -> ConversationReturn.ReannounceOffer(origin.content)
    }

    /** Matching title beats a fuzzy mention; ambiguity never silently switches work. */
    fun resolveTitle(title: String, catalog: List<ArticleIdentity>, confirmationUsed: Boolean,
                     publisher: String? = null, publishedAtText: String? = null): TitleResolution {
        fun normalized(value: String) = value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
        val requested = normalized(title)
        if (requested.isEmpty()) return TitleResolution.KeepCurrentWork
        val available = catalog.filter { (publisher == null || normalized(it.publisher) == normalized(publisher)) &&
            (publishedAtText == null || it.publishedAtText == publishedAtText) }
        val exact = available.filter { normalized(it.title) == requested || normalized(it.voiceTitle) == requested }
        if (exact.size == 1) return TitleResolution.Selected(exact.single())
        val candidates = exact.ifEmpty { available.filter { requested in normalized(it.title) || requested in normalized(it.voiceTitle) } }
        return if (candidates.isNotEmpty() && !confirmationUsed) TitleResolution.AskOnce(candidates)
               else TitleResolution.KeepCurrentWork
    }
}
