package io.github.driveenglishlab.pleos.data

import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface Source6ArticleQuestionResult {
    data class Answer(val operationId: UUID, val value: WireSource6InferenceQAResponse, val returnPlan: ConversationReturn) : Source6ArticleQuestionResult
    data class Failed(val operationId: UUID, val code: String, val returnPlan: ConversationReturn) : Source6ArticleQuestionResult
    data object Superseded : Source6ArticleQuestionResult
    data object Busy : Source6ArticleQuestionResult
}

/** Source6 codec adapter for ephemeral article QA. Uses the shared conversation return
 * policy, authenticated deadline, same-operation retry and current-owner guard. */
class Source6QuestionRepository(
    private val api: IncrementalLearningApi,
    private val isCurrent: suspend (OwnerContext, QuestionOrigin) -> Boolean,
    private val elapsedMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val lane = Mutex()
    private var pending: UUID? = null
    private data class Saved(val scope: LearningCallScope, val origin: QuestionOrigin,
        val request: WireSource6InferenceQARequest, val beganAt: Long)
    private var saved: Saved? = null

    fun resolveTitle(title: String, catalog: List<ArticleIdentity>, confirmationUsed: Boolean,
        publisher: String? = null, publishedAtText: String? = null): TitleResolution =
        ConversationPolicy.resolveTitle(title, catalog, confirmationUsed, publisher, publishedAtText)

    suspend fun interrupt() = lane.withLock { pending = null }

    suspend fun ask(scope: LearningCallScope, article: SegmentContentRef, origin: QuestionOrigin,
        questionText: String, channel: InteractionChannel, responseLanguage: String = "auto",
        questionIntent: String = "content", targetSpan: WireSource6TargetSpan? = null): Source6ArticleQuestionResult {
        require(questionText.isNotBlank() && questionText.codePointCount(0, questionText.length) in 1..500)
        require(responseLanguage in setOf("auto", "ko", "en"))
        require(questionIntent in setOf("content", "language", "mixed", "unclear"))
        val operation = UUID.randomUUID()
        if (!lane.withLock { if (pending != null) false else { pending = operation; true } }) return Source6ArticleQuestionResult.Busy
        try {
            if (!api.isCurrent(scope) || !isCurrent(scope.owner, origin)) { clear(operation); return Source6ArticleQuestionResult.Superseded }
            val request = WireSource6InferenceQARequest("6", operation.toString(), LearningJson.wire(channel), scope.owner.source6Context(),
                article.source6Wire(), questionText, responseLanguage, questionIntent, targetSpan)
            val original = Saved(scope, origin, request, elapsedMs())
            lane.withLock { saved = original }
            return perform(original)
        } catch (error: CancellationException) { clear(operation); throw error }
        catch (error: Exception) { clear(operation); throw error }
    }

    /** The original owner/context/body are immutable; changing context requires a new
     * question rather than sending altered bytes under the earlier operation ID. */
    suspend fun retry(scope: LearningCallScope): Source6ArticleQuestionResult {
        val original = lane.withLock {
            val value = saved ?: return@withLock null
            if (pending != null || scope.owner != value.scope.owner || scope.session.sessionId != value.scope.session.sessionId ||
                scope.session.installationId != value.scope.session.installationId || elapsedMs() - value.beganAt >= 60_000) return@withLock null
            pending = UUID.fromString(value.request.operationId);value
        } ?: return Source6ArticleQuestionResult.Superseded
        return try { perform(original) }
        catch (error: CancellationException) { clear(UUID.fromString(original.request.operationId)); throw error }
        catch (error: Exception) { clear(UUID.fromString(original.request.operationId)); throw error }
    }

    private suspend fun perform(original: Saved): Source6ArticleQuestionResult {
        val operation = UUID.fromString(original.request.operationId)
        if (!api.isCurrent(original.scope) || !isCurrent(original.scope.owner, original.origin)) { clear(operation);return Source6ArticleQuestionResult.Superseded }
        val reply = api.call("source6", "/v2/article-questions", "post", original.scope,
            api.payload("source6", "InferenceQARequest", original.request, WireSource6InferenceQARequest.serializer()), WireSource6InferenceQAResponse.serializer())
        if (!clear(operation) || !isCurrent(original.scope.owner, original.origin)) return Source6ArticleQuestionResult.Superseded
        val plan = ConversationPolicy.returnTo(original.origin)
        return when (reply) {
            is LearningReply.Stored -> Source6ArticleQuestionResult.Answer(operation, reply.value, plan)
            is LearningReply.Failed -> if (reply.code == "SESSION_CHANGED") Source6ArticleQuestionResult.Superseded
                else Source6ArticleQuestionResult.Failed(operation, reply.code, plan)
        }
    }
    private suspend fun clear(id: UUID) = lane.withLock { if (pending != id) false else { pending = null;true } }
}
