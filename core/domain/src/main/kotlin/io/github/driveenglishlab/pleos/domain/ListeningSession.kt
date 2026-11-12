package io.github.driveenglishlab.pleos.domain

import java.util.UUID

enum class ListeningMediaCommand { START, PAUSE, RESUME, NEXT, STOP }
sealed interface ListeningCommandResult {
    data object Accepted : ListeningCommandResult
    data class Unavailable(val reason: String) : ListeningCommandResult
}
fun interface ListeningCommandPort { suspend fun execute(owner: OwnerContext, command: ListeningMediaCommand): ListeningCommandResult }
/** Cancels current input/wait and freezes playback before an explicit destructive work switch. */
fun interface ListeningTransitionPort { suspend fun freezeCurrent(owner: OwnerContext): Boolean }

data class ArticleOfferPrompt(val owner: OwnerContext, val content: ContentRef, val outputId: UUID, val text: String)

/** Separate from a speaking lesson: news-only users can receive an article offer. */
class ListeningOffer(private val owner: OwnerContext, private val content: ContentRef, private val hasNext: Boolean,
    private val outputCompletion: Capability) {
    private val clock = DialogueClock()
    private var output: UUID? = null
    fun prepare(title: String, practiceCompleted: Boolean): ArticleOfferPrompt {
        require(title.isNotBlank() && title.codePointCount(0,title.length) in 1..500)
        val id = UUID.randomUUID(); output = id; clock.expectOutput(WaitKind.ARTICLE_OFFER,id,owner)
        return ArticleOfferPrompt(owner,content,id,ListeningPolicy.articleOffer(title,hasNext,practiceCompleted))
    }
    fun actualOutputEnded(prompt: ArticleOfferPrompt, atMs: Long): Boolean = outputCompletion is Capability.Verified &&
        prompt.owner == owner && prompt.content == content && prompt.outputId == output && clock.outputCompleted(prompt.outputId,owner,atMs)
    fun tick(atMs: Long, canReply: Boolean, inputBusy: Boolean, activeOwner: OwnerContext): WaitAction? {
        val action = clock.tick(atMs,canReply && !inputBusy,activeOwner) ?: return null
        return if (action == WaitAction.NEXT_ARTICLE && !hasNext) WaitAction.SAVE_AND_PAUSE else action
    }
    fun stop() { clock.cancel(); output = null }
}
