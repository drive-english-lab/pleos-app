package io.github.driveenglishlab.pleos.data

import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface ArticleQuestionResult {
    data class Answer(val operationId: UUID, val value: WireInferenceQAResponse, val returnPlan: ConversationReturn) : ArticleQuestionResult
    data class Failed(val operationId: UUID, val code: String, val returnPlan: ConversationReturn) : ArticleQuestionResult
    data object Superseded : ArticleQuestionResult
    data object Busy : ArticleQuestionResult
}

/** Ephemeral QA, independent of attempts. Returns plans/data; never moves playback itself. */
class QuestionRepository(private val api: LearningApi,
    private val isCurrent: suspend (OwnerContext, QuestionOrigin) -> Boolean) {
    private val lane = Mutex()
    private var pending: UUID? = null

    fun resolveTitle(title: String, catalog: List<ArticleIdentity>, confirmationUsed: Boolean,
        publisher: String? = null, publishedAtText: String? = null): TitleResolution =
        ConversationPolicy.resolveTitle(title, catalog, confirmationUsed, publisher, publishedAtText)

    suspend fun interrupt() = lane.withLock { pending = null }

    suspend fun ask(scope: LearningCallScope, askedArticle: ContentRef, origin: QuestionOrigin,
        questionText: String, channel: InteractionChannel, responseLanguage: String = "auto"): ArticleQuestionResult {
        require(questionText.isNotBlank() && questionText.codePointCount(0, questionText.length) in 1..500)
        require(responseLanguage in setOf("auto", "ko", "en"))
        val operation = UUID.randomUUID()
        val accepted = lane.withLock { if (pending != null) false else { pending = operation; true } }
        if (!accepted) return ArticleQuestionResult.Busy
        try {
            if (!isCurrent(scope.owner, origin)) { clear(operation); return ArticleQuestionResult.Superseded }
            val request = WireInferenceQARequest(schemaVersion = "5", operationId = operation.toString(),
                interactionChannel = LearningJson.wire(channel), clientContext = WireCommonClientContext(
                    profileId = scope.owner.profileId.toString(), privacyEpoch = scope.owner.privacyEpoch,
                    contextRevision = scope.owner.revision, presentationToken = scope.owner.presentationToken.toString()),
                contentRef = learningJson.decodeFromString(WireCommonContentRef.serializer(), LearningJson.content(askedArticle).toString()),
                questionText = questionText, responseLanguage = responseLanguage)
            val reply = api.answerArticleQuestion(scope, request)
            if (!clear(operation) || !isCurrent(scope.owner, origin)) return ArticleQuestionResult.Superseded
            val plan = ConversationPolicy.returnTo(origin)
            return when (reply) {
                is LearningReply.Stored -> ArticleQuestionResult.Answer(operation, reply.value, plan)
                is LearningReply.Failed -> if (reply.code == "SESSION_CHANGED") ArticleQuestionResult.Superseded
                    else ArticleQuestionResult.Failed(operation, reply.code, plan)
            }
        } catch (error: CancellationException) { clear(operation); throw error }
        catch (error: Exception) { clear(operation); throw error }
    }

    private suspend fun clear(operation: UUID): Boolean = lane.withLock {
        if (pending != operation) false else { pending = null; true }
    }
}
