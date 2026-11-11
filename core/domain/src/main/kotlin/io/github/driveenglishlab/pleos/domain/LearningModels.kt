package io.github.driveenglishlab.pleos.domain

import java.util.UUID

enum class ListeningMode { ORIGINAL_FULL, BRIEFING }
enum class Followup { EN_SUMMARY, KO_SUMMARY, KO_TRANSLATION, NONE }
enum class HelpKind { FACT_EXPLANATION, SENTENCE_STARTER, PARTIAL_CORRECTION, COMPLETE_EXAMPLE }
enum class CaptureFidelity { UNKNOWN, CONFIRMED_FORM, LITERAL_CONTRACT_VERIFIED }
enum class Judgement { SUFFICIENT, NEEDS_MORE, FACTUALLY_INCORRECT, UNASSESSABLE }

data class ContentRef(
    val articleId: UUID,
    val bundleId: UUID,
    val sourceSnapshotId: UUID,
    val listeningScriptId: UUID,
    val listeningMode: ListeningMode,
)

data class PracticeRef(val content: ContentRef, val taskId: UUID)

data class LearningSettings(
    val topics: Set<String> = emptySet(),
    val listeningMode: ListeningMode = ListeningMode.ORIGINAL_FULL,
    val followup: Followup? = null,
    val explicitFields: Set<String> = emptySet(),
    val onboardingComplete: Boolean = false,
    val version: Long = 1,
) {
    init {
        require(topics.size <= 20 && topics.all { it.codePointCount(0, it.length) in 1..80 })
        require(explicitFields.size <= 3 && explicitFields.all {
            it in setOf("topics", "listening_mode", "followup_policy")
        })
        require(version >= 1)
        require(!onboardingComplete || followup != null)
    }
}

data class HelpSnapshot(val kinds: Set<HelpKind> = emptySet(), val refs: Set<String> = emptySet()) {
    init {
        require(kinds.size <= 4 && refs.size <= 16)
        require(refs.all { it.codePointCount(0, it.length) in 1..160 })
    }

    fun union(other: HelpSnapshot) = HelpSnapshot(kinds + other.kinds, refs + other.refs)
}

/** Every late result must match this context before it can become visible or audible. */
data class OwnerContext(
    val profileId: UUID,
    val privacyEpoch: Long,
    val revision: Long,
    val presentationToken: UUID,
) {
    init { require(privacyEpoch >= 0 && revision >= 0) }
}

enum class VehiclePosition { PARKED, DRIVE_STOPPED, MOVING_OR_REVERSE_NEUTRAL, UNKNOWN }
enum class PowerAvailability { UNKNOWN, ALLOWED, DISALLOWED, SUSPENDING, SUSPENDED }

data class SafetyState(
    val position: VehiclePosition = VehiclePosition.UNKNOWN,
    val uxKnown: Boolean = false,
    val detailsAllowed: Boolean = false,
    val directInputAllowed: Boolean = false,
    val systemBlocked: Boolean = false,
    val generation: Long = 0,
)

data class WindowState(
    val visible: Boolean = false,
    val focused: Boolean = false,
    val generation: Long = 0,
)

data class AccessState(
    val safety: SafetyState = SafetyState(),
    val window: WindowState = WindowState(),
    val power: PowerAvailability = PowerAvailability.UNKNOWN,
    val microphoneGranted: Boolean = false,
    val directProviderVerified: Boolean = false,
    val inputArbitrationVerified: Boolean = false,
    val audioAllowsReply: Boolean = false,
    val ownerValid: Boolean = false,
) {
    val detailsOpen: Boolean get() = ownerValid && safety.position == VehiclePosition.PARKED &&
        safety.uxKnown && safety.detailsAllowed && !safety.systemBlocked && window.visible &&
        power == PowerAvailability.ALLOWED

    val canCapture: Boolean get() = detailsOpen && safety.directInputAllowed && window.focused &&
        microphoneGranted && directProviderVerified && inputArbitrationVerified && audioAllowsReply
}

enum class PlaybackIntent { PLAY, USER_PAUSED, STOPPED }
enum class ListeningStage { MAIN, FOLLOWUP, ARTICLE_OFFER }
data class PlaybackPosition(val assetId: UUID, val stage: ListeningStage, val positionMs: Long, val durationMs: Long) {
    init { require(durationMs > 0 && positionMs in 0..durationMs) }
}

data class ReturnPoint(val playback: PlaybackPosition, val intent: PlaybackIntent) {
    fun afterConversation(): ReturnPoint = copy(playback = playback.copy(positionMs = (playback.positionMs - 5_000).coerceAtLeast(0)))
}
