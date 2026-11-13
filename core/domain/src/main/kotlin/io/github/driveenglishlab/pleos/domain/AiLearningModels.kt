package io.github.driveenglishlab.pleos.domain

import java.time.Instant
import java.util.UUID

/** Product bands group CEFR levels; they are preferences, not certified proficiency. */
enum class LearningBand(val cefrLevels: Set<String>) {
    BASIC(setOf("A1", "A2")), INTERMEDIATE(setOf("B1", "B2")), ADVANCED(setOf("C1", "C2"))
}

data class LearningBandChoice(val choiceId: UUID, val band: LearningBand)
enum class MaterialAssessmentStatus { VERIFIED, UNKNOWN, REVIEW_REQUIRED }
enum class LearningMaterialStatus { READY, PRACTICE_UNAVAILABLE, WITHDRAWN }
enum class RecommendationStatus {
    INSUFFICIENT_EVIDENCE, PENDING, READY, DEFERRED, KEPT, DISMISSED, APPLIED, STALE, UNAVAILABLE
}
enum class RecommendationAction { APPLY, KEEP, DISMISS }
enum class RecommendationCoverage { COMPLETE, UNKNOWN }

/** Identity of immutable evidence. Preference changes and HTTP arrival times are not evidence. */
data class RecommendationBasis(val profile: ProfileContext, val evidenceRevision: Long, val signature: String) {
    init { require(evidenceRevision >= 0); requireReference(signature) }
}

data class LearningRecommendationResult(
    val recommendationId: UUID,
    val version: Long,
    val basis: RecommendationBasis,
    val band: LearningBand?,
    val evaluatedAt: Instant?,
    val rubricVersion: String,
    val policyVersion: String,
) {
    init { require(version >= 1); requireReference(rubricVersion); requireReference(policyVersion) }
}
