package io.github.driveenglishlab.pleos.domain

enum class HelpRequest { FACT, HINT, EXAMPLE, QUESTION_MEANING, SIMPLE_ENGLISH }
enum class SupportAction { FACT_EXPLANATION, SENTENCE_STARTER, COMPLETE_EXAMPLE, QUESTION_MEANING, SIMPLE_ENGLISH }

data class SupportPack(
    val ref: String,
    val coreKo: List<String>,
    val coreEn: List<String>,
    val starterEn: String,
    val exampleEn: String,
    val questionMeaningKo: String,
) {
    init {
        require(ref.codePointCount(0, ref.length) in 1..120)
        require(coreKo.size in 1..2 && coreEn.size in 1..2)
        fun sentences(text: String) = text.split(Regex("[.!?。！？]+(?:\\s+|$)")).count { it.isNotBlank() }
        require(coreKo.sumOf(::sentences) <= 2 && coreEn.sumOf(::sentences) <= 2)
        require((coreKo + coreEn + listOf(starterEn, exampleEn, questionMeaningKo)).all {
            it.isNotBlank() && it.codePointCount(0, it.length) <= 500
        })
    }
}

data class ProvidedSupport(val text: String, val language: String, val help: HelpSnapshot)

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

    /** Pack sentences/facts must already be reviewed against the frozen listening script. */
    fun provide(request: HelpRequest, pack: SupportPack, received: HelpSnapshot): ProvidedSupport {
        val action = help(request, received)
        val kind = when (action) {
            SupportAction.FACT_EXPLANATION, SupportAction.SIMPLE_ENGLISH -> HelpKind.FACT_EXPLANATION
            SupportAction.SENTENCE_STARTER -> HelpKind.SENTENCE_STARTER
            SupportAction.COMPLETE_EXAMPLE -> HelpKind.COMPLETE_EXAMPLE
            SupportAction.QUESTION_MEANING -> null
        }
        val text = when (action) {
            SupportAction.FACT_EXPLANATION -> pack.coreKo.joinToString(" ")
            SupportAction.SIMPLE_ENGLISH -> pack.coreEn.joinToString(" ")
            SupportAction.SENTENCE_STARTER -> pack.starterEn
            SupportAction.COMPLETE_EXAMPLE -> pack.exampleEn
            SupportAction.QUESTION_MEANING -> pack.questionMeaningKo
        }
        val language = if (action in setOf(SupportAction.FACT_EXPLANATION, SupportAction.QUESTION_MEANING)) "ko" else "en"
        val snapshot = received.union(HelpSnapshot(setOfNotNull(kind), setOf("${pack.ref}:${action.name.lowercase()}")))
        return ProvidedSupport(text, language, snapshot)
    }

    fun partialListeningSupport(pack: SupportPack, received: HelpSnapshot): ProvidedSupport =
        provide(HelpRequest.SIMPLE_ENGLISH, pack, received)

    fun mayOfferExampleAfterRetry(validRetryUsed: Boolean, stillStuck: Boolean): Boolean = validRetryUsed && stillStuck
}
