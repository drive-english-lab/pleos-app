package io.github.driveenglishlab.pleos.domain

import java.time.Instant
import java.util.UUID

/** These are UI read filters, never profile-interest or playback-selection commands. */
enum class CatalogView { INTERESTS, ISSUES }
enum class CatalogCategoryId { TECHNOLOGY, BUSINESS, WORLD, SCIENCE, ENVIRONMENT, CULTURE }
enum class CatalogAvailability { READY, EMPTY, PENDING, FAILED, OFFLINE_SNAPSHOT, SNAPSHOT_STALE, UNAVAILABLE }
data class CatalogArticleKey(val articleId: UUID, val sourceSnapshotId: UUID)
data class CatalogCategory(val id: CatalogCategoryId, val displayName: String, val active: Boolean, val displayOrder: Int) {
    init { require(displayName.isNotBlank() && displayOrder in 0..5) }
}
enum class CatalogComponentStatus { UNPREPARED, PREPARING, READY, FAILED, UNAVAILABLE }
data class CatalogComponent(val kind: String, val status: CatalogComponentStatus, val reason: String?, val retryable: Boolean)
enum class EstimateStatus { ESTIMATED, UNKNOWN, NOT_APPLICABLE }
enum class EstimateUnit { WORD, HANGUL_SYLLABLE }
/** Technical estimates only, separate from a real audio duration or time spent learning. */
data class IndependentEstimate(val status: EstimateStatus, val estimatedMs: Long?, val scriptId: UUID?, val scriptHash: String?,
    val language: SegmentLanguage?, val policyId: String?, val policyRevision: String?, val unit: EstimateUnit?, val unitCount: Long?) {
    init {
        scriptHash?.let { require(it.matches(Regex("[a-f0-9]{64}"))) }
        unitCount?.let { require(it >= 0) }
        if (status == EstimateStatus.ESTIMATED) require(estimatedMs != null && estimatedMs >= 1 && scriptId != null && scriptHash != null &&
            language != null && !policyId.isNullOrBlank() && !policyRevision.isNullOrBlank() && unit != null && unitCount != null && unitCount >= 1)
        else require(estimatedMs == null)
    }
    companion object {
        fun total(main: IndependentEstimate?, followup: IndependentEstimate?): Long? {
            val first = main?.takeIf { it.status == EstimateStatus.ESTIMATED }?.estimatedMs ?: return null
            val second = when (followup?.status) { EstimateStatus.ESTIMATED -> requireNotNull(followup.estimatedMs); EstimateStatus.NOT_APPLICABLE -> 0L; else -> return null }
            return if (first <= Long.MAX_VALUE - second) first + second else null
        }
    }
}
data class CatalogHeadlineObservation(val observationId: UUID, val routeId: String, val scopeRevision: Long,
    val observedAt: Instant, val entryHash: String) {
    init { require(scopeRevision >= 1 && routeId.isNotBlank() && entryHash.matches(Regex("[a-f0-9]{64}"))) }
}
enum class OriginIndependence { CONFIRMED, PENDING }
data class CatalogIssueEvidence(val headlinePlacementConfirmed: Boolean, val headlineObservations: List<CatalogHeadlineObservation>,
    val independentOriginGroups: Set<String>, val independentOriginCount: Int?, val independence: OriginIndependence,
    val latestOriginalPublishedAt: Instant?, val asOf: Instant, val policyRevision: String) {
    init {
        require(policyRevision.isNotBlank() && headlineObservations.map { it.observationId }.distinct().size == headlineObservations.size)
        require(headlineObservations.all { it.observedAt <= asOf })
        if (independence == OriginIndependence.CONFIRMED) require(independentOriginCount != null && independentOriginCount >= 1 && independentOriginCount == independentOriginGroups.size)
        else require(independentOriginCount == null)
        latestOriginalPublishedAt?.let { require(it <= asOf) }
    }
    val hasCandidateEvidence get() = headlinePlacementConfirmed && headlineObservations.isNotEmpty() ||
        independence == OriginIndependence.CONFIRMED && (independentOriginCount ?: 0) >= 2
}
data class CatalogIssueSummary(val issueId: UUID, val revision: Long, val representativeArticleId: UUID,
    val memberArticleIds: Set<UUID>, val evidence: CatalogIssueEvidence, val asOf: Instant, val rankingPolicyRevision: String) {
    init { require(revision >= 1 && memberArticleIds.isNotEmpty() && representativeArticleId in memberArticleIds && rankingPolicyRevision.isNotBlank() && evidence.asOf <= asOf && evidence.hasCandidateEvidence) }
}
data class CatalogArticleCard(val key: CatalogArticleKey, val title: String, val publisher: String, val originalUrl: String,
    val publishedAt: Instant?, val editorialType: String, val topics: Set<String>, val components: List<CatalogComponent>,
    val mainEstimate: IndependentEstimate?, val followupEstimate: IndependentEstimate?, val issue: CatalogIssueSummary? = null) {
    init {
        require(title.isNotBlank() && publisher.isNotBlank() && originalUrl.isNotBlank())
        require(editorialType in setOf("news", "analysis", "institutional_release"))
        issue?.let { require(it.representativeArticleId == key.articleId) }
    }
    val rowKey: String get() = issue?.let { "issue:${it.issueId}" } ?: "article:${key.articleId}:${key.sourceSnapshotId}"
}
data class CatalogSnapshot(val snapshotId: UUID, val revision: Long, val catalogRevision: Long, val viewPolicyRevision: String,
    val collectionAsOf: Instant?, val publishedAsOf: Instant?, val issueAsOf: Instant?) {
    init { require(revision >= 1 && catalogRevision >= 1 && viewPolicyRevision.isNotBlank()) }
}
data class CatalogViewKey(val profile: ProfileContext, val view: CatalogView, val categoryFilter: CatalogCategoryId?)
data class CatalogViewReturnToken(val owner: OwnerContext, val view: CatalogView, val categoryFilter: CatalogCategoryId?,
    val snapshotId: UUID?, val snapshotRevision: Long?, val anchorArticle: CatalogArticleKey?, val navigationGeneration: Long, val token: UUID) {
    init { require((snapshotId == null) == (snapshotRevision == null)); snapshotRevision?.let { require(it >= 1) }; require(navigationGeneration >= 0) }
}
data class CatalogViewState(val owner: OwnerContext? = null, val view: CatalogView = CatalogView.INTERESTS,
    val categoryFilter: CatalogCategoryId? = null, val snapshot: CatalogSnapshot? = null, val items: List<CatalogArticleCard> = emptyList(),
    val categories: List<CatalogCategory> = emptyList(), val availability: CatalogAvailability = CatalogAvailability.UNAVAILABLE,
    val loading: Boolean = false, val nextCursor: String? = null, val errorCode: String? = null) {
    init {
        require(items.map { it.rowKey }.distinct().size == items.size)
        nextCursor?.let { require(it.isNotBlank() && it.length <= 2048 && snapshot != null) }
        if (availability == CatalogAvailability.EMPTY) require(items.isEmpty())
        if (categories.isNotEmpty()) require(categories.map { it.id }.toSet() == CatalogCategoryId.entries.toSet() && categories.map { it.displayOrder }.toSet() == (0..5).toSet())
    }
}

/** No business-mutation effect exists here: switching these reads cannot reset any stream. */
object CatalogViewPolicy {
    fun filter(state: CatalogViewState, view: CatalogView, category: CatalogCategoryId?): CatalogViewState =
        if (state.view == view && state.categoryFilter == category) state
        else CatalogViewState(owner = state.owner, view = view, categoryFilter = category)
    fun mayRestore(token: CatalogViewReturnToken, owner: OwnerContext?, navigationGeneration: Long): Boolean =
        token.owner == owner && token.navigationGeneration == navigationGeneration
    fun automaticSelectionView(): CatalogView = CatalogView.INTERESTS
}
