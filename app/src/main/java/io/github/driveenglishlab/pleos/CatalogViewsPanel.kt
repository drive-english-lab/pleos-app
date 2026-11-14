package io.github.driveenglishlab.pleos

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.driveenglishlab.pleos.domain.*
import io.github.driveenglishlab.pleos.ui.*
import io.github.driveenglishlab.pleos.ui.components.*
import io.github.driveenglishlab.pleos.ui.theme.ProductMetrics
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Browsing emits read intents only. It neither edits interests nor selects/plays an article. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun CatalogViewsPanel(state: ProductScreenState, send: (UiIntent) -> Unit, openSource: (UiOrigin, URI) -> Unit) {
    val catalog = state.catalogViews
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item {
                Text(if (catalog.view == CatalogView.INTERESTS) "내 관심 뉴스" else "최근 주요 이슈",
                    style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CatalogReadChoice("내 관심 뉴스", catalog.view == CatalogView.INTERESTS) { send(UiIntent.CatalogViewChanged(state.origin, CatalogView.INTERESTS)) }
                    CatalogReadChoice("최근 주요 이슈", catalog.view == CatalogView.ISSUES) { send(UiIntent.CatalogViewChanged(state.origin, CatalogView.ISSUES)) }
                }
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CatalogReadChoice("전체 분야", catalog.categoryFilter == null) { send(UiIntent.CatalogFilterChanged(state.origin, null)) }
                    catalog.categories.sortedBy { it.displayOrder }.forEach { category ->
                        CatalogReadChoice(category.displayName, catalog.categoryFilter == category.id, category.active) {
                            send(UiIntent.CatalogFilterChanged(state.origin, category.id))
                        }
                    }
                }
            }
            item {
                val snapshot = catalog.snapshot
                Text(snapshot?.publishedAsOf?.let { "목록 갱신 · ${catalogTime(it)}" } ?: "목록 갱신 시각은 아직 확인되지 않았어요",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(snapshot?.collectionAsOf?.let { "자료 확인 · ${catalogTime(it)}" } ?: "자료 확인 시각은 아직 확인되지 않았어요",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (catalog.view == CatalogView.ISSUES) Text(snapshot?.issueAsOf?.let { "이슈 근거 확인 · ${catalogTime(it)}" } ?: "이슈 근거 시각은 아직 확인되지 않았어요",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (catalog.loading) item { ProductNotice("선택한 뉴스 목록을 확인하고 있어요"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (catalog.availability != CatalogAvailability.READY || catalog.items.isEmpty()) item {
                CatalogAvailabilityNotice(catalog) { send(UiIntent.CatalogViewChanged(state.origin, catalog.view)) }
            }
            items(catalog.items, key = { card -> card.rowKey }) { card ->
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(card.title, style = MaterialTheme.typography.titleLarge)
                        Text("${card.publisher} · ${editorialLabel(card.editorialType)}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(card.publishedAt?.let { "원문 게시 · ${catalogTime(it)}" } ?: "원문 게시 시각은 확인되지 않았어요", style = MaterialTheme.typography.bodySmall)
                        card.issue?.let { issue -> CatalogIssueEvidenceText(issue) }
                        Text(estimateLabel("본문", card.mainEstimate), style = MaterialTheme.typography.bodySmall)
                        Text(estimateLabel("후속", card.followupEstimate), style = MaterialTheme.typography.bodySmall)
                        if (card.mainEstimate?.status == EstimateStatus.ESTIMATED || card.followupEstimate?.status == EstimateStatus.ESTIMATED)
                            Text("글 길이로 추정한 듣기 시간이에요. 질문·말하기 시간은 포함하지 않아요", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ProductButton("이 기사 듣기", { send(UiIntent.SelectCatalogArticle(state.origin, card.key, catalog.view)) },
                                enabled = catalog.availability == CatalogAvailability.READY && !catalog.loading && !state.busy, secondary = true)
                            publicSourceUri(card.originalUrl)?.let { uri ->
                                ProductButton("원문 보기", { openSource(state.origin, uri) }, secondary = true)
                            }
                        }
                    }
                }
            }
            if (catalog.nextCursor != null) item {
                ProductButton("기사 더 보기", { send(UiIntent.MoreCatalogView(state.origin)) }, busy = catalog.loading, secondary = true)
            }
        }
        ListeningSelectionControls(state, send)
    }
}

@Composable
private fun CatalogReadChoice(label: String, selected: Boolean, enabled: Boolean = true, choose: () -> Unit) {
    Surface(color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.medium) {
        Box(Modifier.heightIn(min = ProductMetrics.touch).widthIn(min = ProductMetrics.touch)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = choose).padding(horizontal = 24.dp, vertical = 20.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CatalogAvailabilityNotice(catalog: CatalogViewState, retry: () -> Unit) {
    val issue = catalog.view == CatalogView.ISSUES
    val title = when (catalog.availability) {
        CatalogAvailability.READY, CatalogAvailability.EMPTY -> if (issue) "지금 확인된 주요 이슈가 없어요" else "선택한 분야의 뉴스가 없어요"
        CatalogAvailability.PENDING -> if (issue) "이슈 근거를 확인하고 있어요" else "뉴스 제공 여부를 확인하고 있어요"
        CatalogAvailability.FAILED -> if (issue) "주요 이슈를 불러오지 못했어요" else "관심 뉴스를 불러오지 못했어요"
        CatalogAvailability.OFFLINE_SNAPSHOT -> "이전에 확인한 목록이에요"
        CatalogAvailability.SNAPSHOT_STALE -> "뉴스 목록이 바뀌었어요"
        CatalogAvailability.UNAVAILABLE -> "이 뉴스 보기를 지금 이용할 수 없어요"
    }
    val detail = when (catalog.availability) {
        CatalogAvailability.OFFLINE_SNAPSHOT -> "연결 후 새 기사를 고를 수 있어요. 현재 듣던 기사는 유지돼요"
        CatalogAvailability.PENDING -> "본문·출처와 제공 가능한 범위를 확인한 뉴스만 표시해요. 현재 듣던 기사는 유지돼요"
        CatalogAvailability.SNAPSHOT_STALE -> "목록을 다시 확인해 주세요. 듣던 기사와 학습 진행은 유지돼요"
        else -> "분야를 바꾸거나 목록을 다시 확인할 수 있어요. 현재 기사와 학습 진행은 유지돼요"
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ProductButton("목록 다시 확인", retry, busy = catalog.loading, secondary = true)
    }
}

@Composable
private fun CatalogIssueEvidenceText(issue: CatalogIssueSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (issue.evidence.headlinePlacementConfirmed && issue.evidence.headlineObservations.isNotEmpty())
            Text("주요 뉴스에서 확인한 사건이에요", style = MaterialTheme.typography.bodySmall)
        val origins = issue.evidence.independentOriginCount
        Text(if (issue.evidence.independence == OriginIndependence.CONFIRMED && origins != null)
            "확인된 독립 원문 · ${origins}개" else "독립 원문 수는 아직 확인되지 않았어요", style = MaterialTheme.typography.bodySmall)
        Text("관련 기사 · ${issue.memberArticleIds.size}편", style = MaterialTheme.typography.bodySmall)
        Text("이슈 근거 · ${catalogTime(issue.evidence.asOf)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun estimateLabel(name: String, value: IndependentEstimate?): String = when (value?.status) {
    EstimateStatus.ESTIMATED -> value?.estimatedMs?.let { "$name 예상 · 약 ${(it - 1) / 60_000 + 1}분" } ?: "$name 예상 길이 미확인"
    EstimateStatus.NOT_APPLICABLE -> "$name 듣기 없음"
    else -> "$name 예상 길이 미확인"
}
private fun catalogTime(value: Instant): String = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm XXX").format(value.atZone(ZoneId.systemDefault()))
