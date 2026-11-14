package io.github.driveenglishlab.pleos.platform

import io.github.driveenglishlab.pleos.domain.*
import java.util.UUID

/** Exact artifact/output binding. A layout callback or whole-page ACK is not this witness. */
data class PresentationUiBinding(val owner: OwnerContext, val task: PracticeRef, val lessonId: UUID?,
    val ticket: PracticePresentationTicket, val outputId: UUID, val artifact: SupportArtifact)
data class ArtifactWitness(val outputId: UUID, val textHash: String, val mode: ObservationMode, val evidenceRef: String)

class SupportPresenter(private val current: suspend (PresentationUiBinding) -> Boolean,
    private val record: suspend (PresentationUiBinding, PracticePresentationObservation) -> Unit) {
    suspend fun witnessed(binding: PresentationUiBinding, witness: ArtifactWitness): PracticePresentationObservation? {
        if (!current(binding) || witness.outputId != binding.outputId || witness.textHash != binding.artifact.textHash ||
            binding.ticket.presentation.artifacts != listOf(binding.artifact) || witness.mode !in setOf(ObservationMode.PARKED_VISIBLE,
                ObservationMode.VOICE_OUTPUT_COMPLETED, ObservationMode.EXPLICIT_EXAMPLE_CONFIRMATION)) return null
        val observation = PracticePresentationObservation(UUID.nameUUIDFromBytes(("support-observation:${binding.outputId}").toByteArray()),
            binding.ticket.profileContext, UUID.nameUUIDFromBytes(("support-witness:${binding.outputId}").toByteArray()), binding.owner,
            binding.outputId, ObservationOutcome.COMPLETED, witness.mode, witness.evidenceRef)
        record(binding, observation)
        return observation
    }
    suspend fun interrupted(binding: PresentationUiBinding): PracticePresentationObservation {
        val observation = PracticePresentationObservation(UUID.nameUUIDFromBytes(("support-interrupted:${binding.outputId}").toByteArray()),
            binding.ticket.profileContext, UUID.nameUUIDFromBytes(("support-unknown:${binding.outputId}").toByteArray()), binding.owner,
            binding.outputId, ObservationOutcome.PARTIAL_OR_UNKNOWN, ObservationMode.INDETERMINATE, "component-interrupted-before-verified-completion")
        record(binding, observation)
        return observation
    }
}
