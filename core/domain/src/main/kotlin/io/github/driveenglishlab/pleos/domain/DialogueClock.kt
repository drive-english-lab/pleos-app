package io.github.driveenglishlab.pleos.domain

import java.util.UUID

enum class WaitKind { ARTICLE_OFFER, FIRST_ANSWER, HINT_METHOD, RETRY }
enum class WaitAction { NEXT_ARTICLE, ANNOUNCE_HINT_METHOD, SAVE_AND_PAUSE }

/** Uses elapsed (monotonic) milliseconds. No estimated speech length or wall time. */
class DialogueClock {
    private data class Wait(val kind: WaitKind, val token: UUID, val context: OwnerContext,
                            var remainingMs: Long, var lastTickMs: Long? = null)
    private var wait: Wait? = null
    private var expectedOutput: Triple<WaitKind, UUID, OwnerContext>? = null
    private var hintMethodAnnounced = false

    fun newQuestion() { cancel(); hintMethodAnnounced = false }
    fun restore(progress: DialogueProgress) { cancel(); hintMethodAnnounced = progress.hintMethodAnnounced }
    fun businessProgress() = DialogueProgress(hintMethodAnnounced)
    fun recordBusinessProgress(progress: DialogueProgress) { hintMethodAnnounced = hintMethodAnnounced || progress.hintMethodAnnounced }

    fun expectOutput(kind: WaitKind, token: UUID, context: OwnerContext) {
        wait = null
        expectedOutput = Triple(kind, token, context)
    }

    /** Only the adapter's verified completion for this exact output may start a wait. */
    fun outputCompleted(token: UUID, context: OwnerContext, nowMs: Long): Boolean {
        val pending = expectedOutput ?: return false
        if (pending.second != token || pending.third != context) return false
        val duration = when (pending.first) {
            WaitKind.ARTICLE_OFFER, WaitKind.RETRY -> 8_000L
            WaitKind.FIRST_ANSWER -> 20_000L
            WaitKind.HINT_METHOD -> 10_000L
        }
        if (pending.first == WaitKind.HINT_METHOD) hintMethodAnnounced = true
        wait = Wait(pending.first, token, context, duration, nowMs)
        expectedOutput = null
        return true
    }

    /** Must be called on input/focus/window/power edges as well as during normal ticks. */
    fun tick(nowMs: Long, canReply: Boolean, context: OwnerContext): WaitAction? {
        val current = wait ?: return null
        if (current.context != context) { cancel(); return null }
        require(nowMs >= 0)
        val previous = current.lastTickMs
        current.lastTickMs = if (canReply) nowMs else null
        if (!canReply || previous == null) return null
        current.remainingMs -= (nowMs - previous).coerceAtLeast(0)
        if (current.remainingMs > 0) return null
        wait = null
        return when (current.kind) {
            WaitKind.ARTICLE_OFFER -> WaitAction.NEXT_ARTICLE
            WaitKind.FIRST_ANSWER -> if (hintMethodAnnounced) WaitAction.SAVE_AND_PAUSE else WaitAction.ANNOUNCE_HINT_METHOD
            WaitKind.HINT_METHOD, WaitKind.RETRY -> WaitAction.SAVE_AND_PAUSE
        }
    }

    /** Thinking/requested help needs actual response/re-question completion before a fresh 20s. */
    fun thinkingOrHelp(token: UUID, context: OwnerContext) = expectOutput(WaitKind.FIRST_ANSWER, token, context)
    fun cancel() { wait = null; expectedOutput = null }
}
