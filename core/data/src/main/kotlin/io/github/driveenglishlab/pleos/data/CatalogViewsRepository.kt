package io.github.driveenglishlab.pleos.data

import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Passive negotiated reads only: no preparation/selection/settings/stream/intent write API. */
class CatalogViewsRepository(private val api: IncrementalLearningApi, private val codecs: IncrementalContractJson,
    private val dao: ServiceIntentDao) {
    private val reads = Mutex()
    @Volatile private var token = UUID.randomUUID()
    private val mutable = MutableStateFlow(CatalogViewState())
    val state = mutable.asStateFlow()

    suspend fun bind(scope: LearningCallScope, view: CatalogView = CatalogView.INTERESTS, category: CatalogCategoryId? = null) {
        val original = UUID.randomUUID(); token = original
        mutable.value = CatalogViewState(scope.owner, view, category)
        if (!api.isCurrent(scope)) { clear(); return }
        val latest = dao.latestCatalog(scope.owner.profileId.toString(), scope.owner.privacyEpoch, view.name.lowercase(), filter(category)) ?: return
        if (latest.ownerRevision != scope.owner.revision || !current(scope, original, view, category)) return
        try {
            val pages = dao.catalogPages(latest.profileId, latest.privacyEpoch, latest.view, latest.filterKey, latest.snapshotId)
            val response = pages.map { codecs.decode("catalog-views", "CatalogViewResponse", it.payloadJson, WireCatalogViewsCatalogViewResponse.serializer()) }
            val restored = aggregate(scope, response)
            if (current(scope, original, view, category)) mutable.value = restored.copy(availability = CatalogAvailability.OFFLINE_SNAPSHOT, loading = false, errorCode = "CURRENT_READ_REQUIRED")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (current(scope, original, view, category)) mutable.value = CatalogViewState(scope.owner, view, category, errorCode = "INVALID_CACHED_SNAPSHOT") }
    }
    fun clear() { token = UUID.randomUUID(); mutable.value = CatalogViewState() }

    suspend fun refresh(scope: LearningCallScope, limit: Long = 20): LearningReply<CatalogViewState> = read(scope, false, limit)
    suspend fun more(scope: LearningCallScope, limit: Long = 20): LearningReply<CatalogViewState> = read(scope, true, limit)

    private suspend fun read(scope: LearningCallScope, more: Boolean, limit: Long): LearningReply<CatalogViewState> {
        require(limit in 1..50)
        val start = mutable.value
        val original = token
        if (start.owner != scope.owner || !api.isCurrent(scope)) return LearningReply.Failed("SESSION_CHANGED", false)
        val cursor = if (more) start.nextCursor ?: return LearningReply.Stored(scope, start) else null
        if (more && start.availability in setOf(CatalogAvailability.OFFLINE_SNAPSHOT, CatalogAvailability.SNAPSHOT_STALE, CatalogAvailability.UNAVAILABLE))
            return LearningReply.Failed("CURRENT_FIRST_PAGE_REQUIRED", false)
        mutable.value = start.copy(loading = true, errorCode = null)
        return reads.withLock {
            if (!current(scope, original, start.view, start.categoryFilter)) return@withLock LearningReply.Failed("VIEW_CHANGED", false)
            when (val reply = api.call("catalog-views", "/v2/content/catalog", "get", scope, null, WireCatalogViewsCatalogViewResponse.serializer(),
                queryArguments = mapOf("catalog_view_contract_version" to "1", "view" to start.view.name.lowercase(),
                    "category_filter_id" to start.categoryFilter?.name?.lowercase(), "limit" to limit.toString(), "cursor" to cursor))) {
                is LearningReply.Failed -> {
                    if (current(scope, original, start.view, start.categoryFilter)) {
                        val availability = if (reply.code == "snapshot_stale") CatalogAvailability.SNAPSHOT_STALE
                            else if (start.snapshot != null) CatalogAvailability.OFFLINE_SNAPSHOT else CatalogAvailability.UNAVAILABLE
                        mutable.value = start.copy(availability = availability, loading = false, errorCode = reply.code,
                            nextCursor = if (availability == CatalogAvailability.SNAPSHOT_STALE) null else start.nextCursor)
                    }
                    reply
                }
                is LearningReply.Stored -> try {
                    val value = reply.value
                    require(value.view == start.view.name.lowercase() && value.categoryFilterId == start.categoryFilter?.name?.lowercase())
                    require(value.nextCursor == null || value.nextCursor != cursor)
                    val page = project(scope, value)
                    if (!current(scope, original, start.view, start.categoryFilter)) return@withLock LearningReply.Failed("VIEW_CHANGED", false)
                    val combined = if (!more) page else {
                        require(start.snapshot == page.snapshot && start.categories == page.categories)
                        require(start.items.none { old -> page.items.any { it.rowKey == old.rowKey } })
                        page.copy(items = start.items + page.items)
                    }
                    verifyOrder(combined)
                    val payload = codecs.encode("catalog-views", "CatalogViewResponse", value, WireCatalogViewsCatalogViewResponse.serializer())
                    val sequence = dao.catalogSequence(scope.owner.profileId.toString(), scope.owner.privacyEpoch, value.view, filter(start.categoryFilter)) + 1
                    dao.saveCatalog(scope.owner, LocalCatalogViewPage(scope.owner.profileId.toString(), scope.owner.privacyEpoch, scope.owner.revision,
                        value.view, filter(start.categoryFilter), value.snapshotId, cursor ?: "", sequence, payload, LearningJson.hash(payload)))
                    if (!current(scope, original, start.view, start.categoryFilter)) return@withLock LearningReply.Failed("VIEW_CHANGED", false)
                    mutable.value = combined
                    LearningReply.Stored(scope, combined)
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (_: Exception) {
                    if (current(scope, original, start.view, start.categoryFilter)) mutable.value = start.copy(loading = false, errorCode = "INVALID_CATALOG_SNAPSHOT", availability = CatalogAvailability.UNAVAILABLE)
                    LearningReply.Failed("INVALID_CATALOG_SNAPSHOT", false)
                }
            }
        }
    }

    fun returnToken(navigationGeneration: Long, anchor: CatalogArticleKey? = null): CatalogViewReturnToken? {
        val value = mutable.value
        return value.owner?.let { CatalogViewReturnToken(it, value.view, value.categoryFilter, value.snapshot?.snapshotId, value.snapshot?.revision, anchor, navigationGeneration, UUID.randomUUID()) }
    }
    private suspend fun current(scope: LearningCallScope, original: UUID, view: CatalogView, category: CatalogCategoryId?): Boolean =
        token == original && mutable.value.owner == scope.owner && mutable.value.view == view && mutable.value.categoryFilter == category && api.isCurrent(scope)
    private fun filter(value: CatalogCategoryId?) = value?.name?.lowercase() ?: ""
    private fun project(scope: LearningCallScope, value: WireCatalogViewsCatalogViewResponse): CatalogViewState {
        require(value.catalogViewContractVersion == 1L && value.profileContext.profileId == scope.owner.profileId.toString() && value.profileContext.privacyEpoch == scope.owner.privacyEpoch)
        val categories = value.categoryConfig.categories.map { CatalogCategory(CatalogCategoryId.valueOf(it.id.uppercase()), it.displayName, it.active, exactInt(it.displayOrder)) }
        val snapshot = CatalogSnapshot(UUID.fromString(value.snapshotId), value.snapshotRevision, value.catalogRevision, value.viewPolicyRevision,
            value.collectionAsOf?.let(Instant::parse), value.publishedAsOf?.let(Instant::parse), value.issueAsOf?.let(Instant::parse))
        val view = CatalogView.valueOf(value.view.uppercase())
        val items = value.items.map { item -> when (item) {
            is WireCatalogViewsArticleDiscoveryView -> { require(view == CatalogView.INTERESTS); card(item) }
            is WireCatalogViewsIssueView -> {
                require(view == CatalogView.ISSUES && item.representativeArticleId == item.article.articleId && item.memberArticleIds.distinct().size == item.memberArticleIds.size)
                val evidence = item.evidence
                require(evidence.independentOriginGroups.distinct().size == evidence.independentOriginGroups.size)
                val projected = CatalogIssueEvidence(evidence.headlinePlacementConfirmed, evidence.headlineObservations.map {
                    CatalogHeadlineObservation(UUID.fromString(it.observationId), it.discoveryRouteId, it.headlinesScopeRevision, Instant.parse(it.observedAt), it.entryHash)
                }, evidence.independentOriginGroups.toSet(), evidence.independentOriginCount?.let(::exactInt), OriginIndependence.valueOf(evidence.independenceStatus.uppercase()),
                    evidence.latestOriginalPublishedAt?.let(Instant::parse), Instant.parse(evidence.asOf), evidence.policyRevision)
                val issueAsOf = Instant.parse(item.issueAsOf)
                require(value.issueAsOf != null && issueAsOf <= Instant.parse(value.issueAsOf))
                card(item.article).copy(issue = CatalogIssueSummary(UUID.fromString(item.issueId), item.issueRevision, UUID.fromString(item.representativeArticleId),
                    item.memberArticleIds.map(UUID::fromString).toSet(), projected, issueAsOf, item.rankPolicyRevision))
            }
        } }
        val result = CatalogViewState(scope.owner, view, value.categoryFilterId?.let { CatalogCategoryId.valueOf(it.uppercase()) }, snapshot, items, categories,
            CatalogAvailability.valueOf(value.viewState.uppercase()), false, value.nextCursor, value.reason)
        require(result.availability != CatalogAvailability.READY || result.items.isNotEmpty())
        verifyOrder(result)
        return result
    }
    private fun card(value: WireCatalogViewsArticleDiscoveryView) = CatalogArticleCard(CatalogArticleKey(UUID.fromString(value.articleId), UUID.fromString(value.sourceSnapshotId)),
        value.title, value.source.publisher, value.source.originalUrl, value.source.publishedAtUtc?.let(Instant::parse), value.source.editorialType, value.topics.toSet(),
        value.componentStates.map { CatalogComponent(it.kind, CatalogComponentStatus.valueOf(it.status.uppercase()), it.reason, it.retryable) }, estimate(value.mainEstimate), estimate(value.followupEstimate))
    private fun estimate(value: WireCatalogViewsIndependentEstimate) = IndependentEstimate(EstimateStatus.valueOf(value.status.uppercase()), value.estimatedMs,
        value.scriptId?.let(UUID::fromString), value.scriptHash, value.language?.let { SegmentLanguage.valueOf(it.uppercase()) }, value.policyId, value.policyRevision,
        value.unit?.let { EstimateUnit.valueOf(it.uppercase()) }, value.unitCount)
    private fun aggregate(scope: LearningCallScope, pages: List<WireCatalogViewsCatalogViewResponse>): CatalogViewState {
        require(pages.isNotEmpty())
        val projected = pages.map { project(scope, it) }
        val first = projected.first()
        require(projected.all { it.snapshot == first.snapshot && it.view == first.view && it.categoryFilter == first.categoryFilter && it.categories == first.categories })
        return projected.last().copy(items = projected.flatMap { it.items }).also(::verifyOrder)
    }
    private fun verifyOrder(value: CatalogViewState) {
        val comparator = if (value.view == CatalogView.INTERESTS) compareByDescending<CatalogArticleCard> { it.publishedAt }.thenBy { it.key.articleId.toString() }
            else compareByDescending<CatalogArticleCard> { requireNotNull(it.issue).evidence.headlinePlacementConfirmed }
                .thenByDescending { requireNotNull(it.issue).evidence.independentOriginCount }
                .thenByDescending { requireNotNull(it.issue).evidence.latestOriginalPublishedAt }.thenBy { requireNotNull(it.issue).issueId.toString() }
        require(value.items == value.items.sortedWith(comparator)) { "Server snapshot order changed." }
    }
    private fun exactInt(value: Long): Int { require(value in 0L..Int.MAX_VALUE.toLong()); return value.toInt() }
}
