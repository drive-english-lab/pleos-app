package io.github.driveenglishlab.pleos.domain

import java.security.MessageDigest

enum class HelpRequest { FACT, HINT, EXAMPLE, QUESTION_MEANING, SIMPLE_ENGLISH }
enum class SupportAction { FACT_EXPLANATION, SENTENCE_STARTER, COMPLETE_EXAMPLE, QUESTION_MEANING, SIMPLE_ENGLISH }

data class PreparedSupport(val task: PracticeRef, val artifact: SupportArtifact)
sealed interface SupportPreparation {
    data class Ready(val prepared: PreparedSupport) : SupportPreparation
    data class Unavailable(val reason: String) : SupportPreparation
}

object PracticePolicy {
    fun help(request: HelpRequest, already: HelpSnapshot): SupportAction = when (request) {
        HelpRequest.FACT -> SupportAction.FACT_EXPLANATION
        HelpRequest.HINT -> if (HelpKind.FACT_EXPLANATION in already.kinds) SupportAction.SENTENCE_STARTER else SupportAction.FACT_EXPLANATION
        HelpRequest.EXAMPLE -> SupportAction.COMPLETE_EXAMPLE
        HelpRequest.QUESTION_MEANING -> SupportAction.QUESTION_MEANING
        HelpRequest.SIMPLE_ENGLISH -> SupportAction.SIMPLE_ENGLISH
    }

    fun needsListeningSupport(coverage: String): Boolean {
        require(coverage in setOf("sequential", "partial", "unknown"))
        return coverage != "sequential"
    }

    fun mayCorrectForm(fidelity: CaptureFidelity, uncertainSpans: List<String>, verifiedRoute: Boolean): Boolean =
        verifiedRoute && fidelity != CaptureFidelity.UNKNOWN && uncertainSpans.isEmpty()

    /** Exact reviewed artifact selection only. Preparation never changes help or attempts. */
    fun provide(request: HelpRequest, manifest: PracticeSupportManifest, task: PracticeRef,
        received: HelpSnapshot, requestedLanguage: SupportLanguage? = null): SupportPreparation {
        require(manifest.taskId == task.supportTaskIdentity && manifest.questionRef == task.questionRef)
        val role = when (help(request, received)) {
            SupportAction.FACT_EXPLANATION -> SupportRole.CORE_EXPLANATION
            SupportAction.SENTENCE_STARTER -> SupportRole.SENTENCE_STARTER
            SupportAction.COMPLETE_EXAMPLE -> SupportRole.COMPLETE_EXAMPLE
            SupportAction.QUESTION_MEANING -> SupportRole.QUESTION_MEANING
            SupportAction.SIMPLE_ENGLISH -> SupportRole.SIMPLE_EXPLANATION
        }
        val language = requestedLanguage ?: when (role) {
            SupportRole.QUESTION_MEANING, SupportRole.CORE_EXPLANATION -> SupportLanguage.KO
            else -> SupportLanguage.EN
        }
        val artifact = manifest.find(role, language) ?: return SupportPreparation.Unavailable("requested_language_variant_not_prepared")
        require(artifact.origin == SupportOrigin.PUBLISHED_TASK && artifact.feedbackAttemptId == null)
        val hash = MessageDigest.getInstance("SHA-256").digest(artifact.text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        require(hash == artifact.textHash)
        if (role in setOf(SupportRole.QUESTION_MEANING, SupportRole.CORE_EXPLANATION, SupportRole.SIMPLE_EXPLANATION))
            require(sentenceCount(artifact.text) in 1..2)
        return SupportPreparation.Ready(PreparedSupport(task, artifact))
    }

    /** Caller must journal/verify the real adapter observation before projecting this fact. */
    fun delivered(prepared: PreparedSupport, ticket: PracticePresentationTicket,
        observation: PracticePresentationObservation, received: HelpSnapshot): HelpSnapshot {
        require(ticket.presentation.contentRef == prepared.task && prepared.artifact in ticket.presentation.artifacts)
        require(ticket.clientContext == observation.clientContext && ticket.profileContext == observation.profileContext)
        if (observation.outcome != ObservationOutcome.COMPLETED) return received
        return received.union(HelpSnapshot(setOfNotNull(prepared.artifact.kind), setOf(prepared.artifact.supportRef)))
    }

    fun partialListeningSupport(manifest: PracticeSupportManifest, task: PracticeRef, received: HelpSnapshot): SupportPreparation =
        provide(HelpRequest.SIMPLE_ENGLISH, manifest, task, received)

    fun mayOfferExampleAfterRetry(validRetryUsed: Boolean, stillStuck: Boolean): Boolean = validRetryUsed && stillStuck

    private fun sentenceCount(text: String) = text.split(Regex("[.!?。！？]+(?:\\s+|$)")).count { it.isNotBlank() }
}
