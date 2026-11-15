package io.github.driveenglishlab.pleos.domain

import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class PlaybackRequestKind { EXPLICIT_START, EXPLICIT_NEXT, AUTO_NEXT, EXPLICIT_ARTICLE, READY_ALTERNATIVE }
enum class PlaybackRequestChannel(val budgetMs: Long) { GLEO(6_000), APP(10_000) }
enum class PlaybackRequestState { ACTIVE, EXPIRED, CANCELLED, APPLIED }
enum class PlaybackRequestOutcome { SELECTED, RESUMED, PENDING, EXPIRED_WAITING_EXPLICIT, REQUIRES_EXPLICIT_REPLAY, NO_CANDIDATE }
enum class PendingChoiceKind(val wire:String,val capability:String) {
    ORIGINAL("original_of_pending","pending_original_v1"),
    READY_ALTERNATIVE("ready_alternative_of_pending","pending_ready_alternative_v1"),
    CONTINUE("continue_pending","pending_continue_v1");
    companion object { fun fromWire(value:String)=entries.singleOrNull{it.wire==value} }
}

/** Local observation of the original explicit input, separate from the immutable public request.
 * processToken must change after process/boot restoration, which revokes automatic activation.
 */
data class PlaybackRequestBinding(val owner: OwnerContext, val operationId: UUID, val contextToken: UUID,
    val processToken: UUID, val kind: PlaybackRequestKind, val channel: PlaybackRequestChannel,
    val requestedArticleId: UUID?, val requestedSourceSnapshotId: UUID?, val listeningMode: ListeningMode,
    val requestedAtElapsedMs: Long, val localDeadlineElapsedMs: Long, val returnToken: CatalogViewReturnToken? = null,
    val pendingOriginalParentOperationId:UUID?=null,val pendingChoiceKind:PendingChoiceKind?=null) {
    init {
        require(requestedAtElapsedMs >= 0 && requestedAtElapsedMs <= Long.MAX_VALUE - channel.budgetMs &&
            localDeadlineElapsedMs == requestedAtElapsedMs + channel.budgetMs)
        if (kind == PlaybackRequestKind.EXPLICIT_ARTICLE && pendingOriginalParentOperationId==null) require(requestedArticleId != null && requestedSourceSnapshotId != null)
        if (requestedSourceSnapshotId != null) require(requestedArticleId != null)
        if(pendingOriginalParentOperationId!=null) {
            require(pendingOriginalParentOperationId!=operationId)
            val choice=pendingChoiceKind?:PendingChoiceKind.ORIGINAL
            if(choice==PendingChoiceKind.ORIGINAL)require(listeningMode==ListeningMode.ORIGINAL_FULL &&
                kind in setOf(PlaybackRequestKind.EXPLICIT_START,PlaybackRequestKind.EXPLICIT_NEXT,PlaybackRequestKind.EXPLICIT_ARTICLE))
            if(choice==PendingChoiceKind.READY_ALTERNATIVE)require(kind==PlaybackRequestKind.READY_ALTERNATIVE && requestedArticleId==null)
            if(choice==PendingChoiceKind.CONTINUE)require(kind!=PlaybackRequestKind.AUTO_NEXT)
            require((requestedArticleId==null)==(requestedSourceSnapshotId==null))
        }
        if(pendingChoiceKind!=null)require(pendingOriginalParentOperationId!=null)
        returnToken?.let { require(it.owner == owner) }
    }
}
data class PlaybackAdmission(val operationId: UUID, val requestHash: String, val acceptedAt: Instant,
    val kind: PlaybackRequestKind, val selectionIntentId: UUID?, val activationDeadlineAt: Instant?) {
    init { require(requestHash.matches(Regex("[a-f0-9]{64}"))); require((selectionIntentId == null) == (activationDeadlineAt == null)); activationDeadlineAt?.let { require(it > acceptedAt) } }
}
data class PlaybackRequestIntent(val binding: PlaybackRequestBinding, val admission: PlaybackAdmission? = null,
    val state: PlaybackRequestState = PlaybackRequestState.ACTIVE, val polls: Int = 0,
    val outcomeVersion: Long = 0, val outcome: PlaybackRequestOutcome? = null, val activationClaimId: UUID? = null) {
    init {
        require(polls >= 0 && outcomeVersion >= 0 && (activationClaimId != null) == (state == PlaybackRequestState.APPLIED))
        admission?.let { require(it.operationId == binding.operationId && it.kind == binding.kind) }
    }
}
data class PlaybackActivationGuard(val owner: OwnerContext?, val contextToken: UUID?, val processToken: UUID,
    val elapsedMs: Long, val utcNow: Instant?, val outputAllowed: Boolean, val sameArticleAndFormat: Boolean = true, val access:AccessState?=null) {
    init { require(elapsedMs >= 0) }
    val allowsMediaAdmission:Boolean get()=outputAllowed && (access==null || access.mediaReady)
}
sealed interface PlaybackActivationDecision {
    data class Claimed(val intent: PlaybackRequestIntent) : PlaybackActivationDecision
    data class StateOnly(val intent: PlaybackRequestIntent, val reason: String) : PlaybackActivationDecision
}

object PlaybackRequestPolicy {
    fun admit(intent: PlaybackRequestIntent, admission: PlaybackAdmission): PlaybackRequestIntent {
        require(admission.operationId == intent.binding.operationId && admission.kind == intent.binding.kind)
        admission.activationDeadlineAt?.let { deadline -> require(Duration.between(admission.acceptedAt, deadline).toMillis() in 1L..intent.binding.channel.budgetMs) }
        intent.admission?.let { require(it == admission) { "Original admission/deadline cannot change." } }
        return intent.copy(admission = admission)
    }
    fun observe(intent: PlaybackRequestIntent, version: Long, outcome: PlaybackRequestOutcome): PlaybackRequestIntent {
        require(version >= 1)
        if (version < intent.outcomeVersion) return intent
        if (version == intent.outcomeVersion) { require(intent.outcome == outcome); return intent }
        val expired = outcome == PlaybackRequestOutcome.EXPIRED_WAITING_EXPLICIT && intent.state == PlaybackRequestState.ACTIVE
        return intent.copy(outcomeVersion = version, outcome = outcome, state = if (expired) PlaybackRequestState.EXPIRED else intent.state)
    }
    fun cancel(intent: PlaybackRequestIntent): PlaybackRequestIntent =
        if (intent.state in setOf(PlaybackRequestState.ACTIVE,PlaybackRequestState.EXPIRED)) intent.copy(state = PlaybackRequestState.CANCELLED) else intent
    fun expire(intent: PlaybackRequestIntent): PlaybackRequestIntent = if (intent.state == PlaybackRequestState.ACTIVE) intent.copy(state = PlaybackRequestState.EXPIRED) else intent

    /** Poll count and the original local/server deadlines bound observation independently of jobs. */
    fun reservePoll(intent: PlaybackRequestIntent, guard: PlaybackActivationGuard, maxPolls: Int = 5): PlaybackRequestIntent? {
        require(maxPolls in 1..20)
        if (intent.state != PlaybackRequestState.ACTIVE || intent.polls >= maxPolls || !guard.allowsMediaAdmission || !guard.sameArticleAndFormat || !sameLocalIntent(intent, guard) || expired(intent, guard)) return null
        return intent.copy(polls = intent.polls + 1)
    }
    fun remainingMs(intent: PlaybackRequestIntent, guard: PlaybackActivationGuard): Long {
        if (!sameLocalIntent(intent, guard) || intent.state != PlaybackRequestState.ACTIVE) return 0
        val local = (intent.binding.localDeadlineElapsedMs - guard.elapsedMs).coerceAtLeast(0)
        val deadline = intent.admission?.activationDeadlineAt
        val server = if (deadline != null && guard.utcNow != null) Duration.between(guard.utcNow, deadline).toMillis().coerceAtLeast(0) else local
        return minOf(local, server)
    }
    fun claim(intent: PlaybackRequestIntent, guard: PlaybackActivationGuard, claimId: UUID): PlaybackActivationDecision {
        if (intent.state != PlaybackRequestState.ACTIVE) return PlaybackActivationDecision.StateOnly(intent, intent.state.name)
        if (expired(intent, guard)) return PlaybackActivationDecision.StateOnly(expire(intent), "ORIGINAL_DEADLINE_EXPIRED")
        if (!sameLocalIntent(intent, guard) || !guard.allowsMediaAdmission || !guard.sameArticleAndFormat || guard.utcNow == null)
            return PlaybackActivationDecision.StateOnly(intent, "EXPLICIT_RESUME_REQUIRED")
        if (intent.admission?.selectionIntentId == null || intent.outcome !in setOf(PlaybackRequestOutcome.SELECTED, PlaybackRequestOutcome.RESUMED))
            return PlaybackActivationDecision.StateOnly(intent, "NOT_SELECTED")
        return PlaybackActivationDecision.Claimed(intent.copy(state = PlaybackRequestState.APPLIED, activationClaimId = claimId))
    }
    /** Restoring persisted state never restores a live automatic-output grant. */
    fun restored(intent: PlaybackRequestIntent): PlaybackRequestIntent = expire(intent)
    private fun sameLocalIntent(intent: PlaybackRequestIntent, guard: PlaybackActivationGuard): Boolean =
        intent.binding.owner == guard.owner && intent.binding.contextToken == guard.contextToken && intent.binding.processToken == guard.processToken &&
            guard.elapsedMs >= intent.binding.requestedAtElapsedMs
    private fun expired(intent: PlaybackRequestIntent, guard: PlaybackActivationGuard): Boolean = guard.elapsedMs >= intent.binding.localDeadlineElapsedMs ||
        intent.admission?.activationDeadlineAt?.let { deadline -> guard.utcNow?.let { it >= deadline } } == true
}
