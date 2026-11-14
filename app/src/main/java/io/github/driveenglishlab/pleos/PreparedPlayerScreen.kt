package io.github.driveenglishlab.pleos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.driveenglishlab.pleos.data.SourcePublicScript
import io.github.driveenglishlab.pleos.data.wire.WirePreparationDiscovery
import io.github.driveenglishlab.pleos.data.wire.WireSource6LegacyContentRef6
import io.github.driveenglishlab.pleos.domain.*
import io.github.driveenglishlab.pleos.platform.SegmentRuntimeStatus
import io.github.driveenglishlab.pleos.ui.*
import io.github.driveenglishlab.pleos.ui.components.*
import io.github.driveenglishlab.pleos.ui.theme.ProductMetrics
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Prepared sources keep their segment cursor; no invented duration or legacy bundle. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun PreparedPlayerScreen(state: ProductScreenState, send: (UiIntent) -> Unit, onFormat: (ListeningMode) -> Unit) {
    val work = state.session.preparedListening ?: return
    val source = state.preparedSource?.takeIf { matchesCurrentSource(it, work.content, state.preparedScript) }
    val script = state.preparedScript?.takeIf { it.scriptId == work.cursor.scriptId && it.textHash == work.cursor.scriptHash }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(source?.title ?: "선택한 뉴스", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Text(when (work.stage) {
            ListeningStage.ARTICLE_OFFER -> "다음으로 할 일을 골라 주세요"
            ListeningStage.FOLLOWUP -> "후속 듣기"
            ListeningStage.MAIN -> if (work.content.listeningMode == ListeningMode.BRIEFING) "짧은 영어 브리핑" else "영어 원문 전체"
        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.session.reviewPolicy.context?.selectionId == work.selectionId && state.session.reviewPolicy.effectiveEndPolicy == ReviewEndPolicy.MANUAL_HISTORY)
            Text("기록에서 다시 듣는 기사예요. 다음 뉴스는 직접 골라 주세요", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth >= 1200.dp
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(if (wide) 2.25f else 1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (!wide) PreparedActions(state, send, onFormat)
                    if (state.followupWaiting) {
                        ProductStatus("후속 듣기 자료를 확인하고 있어요", "같은 기사와 듣던 단계는 유지돼요. 다시 확인하거나 후속 듣기를 건너뛸 수 있어요",
                            Modifier.weight(1f), loading = state.busy) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                ProductButton("다시 확인", { send(UiIntent.RetryFollowup(state.origin)) }, busy = state.busy)
                                ProductButton("후속 듣기 건너뛰기", { send(UiIntent.SkipFollowup(state.origin)) }, enabled = !state.busy, secondary = true)
                            }
                        }
                    } else PreparedReader(state, script, Modifier.weight(1f).fillMaxWidth())
                }
                if (wide) Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    PreparedActions(state, send, onFormat)
                    Text("기사 질문은 차량에서 확인된 입력 경로로 이용해요", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (work.stage == ListeningStage.ARTICLE_OFFER) PreparedOffer(state, send)
        PreparedPlaybackDock(state, send)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun PreparedActions(state: ProductScreenState, send: (UiIntent) -> Unit, onFormat: (ListeningMode) -> Unit) {
    val mode = state.session.preparedListening?.content?.listeningMode
    FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ProductButton(if (state.listenOnlyActive) "기본 진행으로 돌아가기" else "오늘은 듣기만", {
            send(UiIntent.ListeningOnly(state.origin, !state.listenOnlyActive))
        }, enabled = !state.busy, secondary = true)
        ProductButton("기사 정보", { send(UiIntent.Navigate(state.origin, SessionScreen.ARTICLE_INFO)) }, secondary = true)
        ProductButton("문장 도움", { send(UiIntent.LoadSentences(state.origin)) }, enabled = !state.busy, secondary = true)
        ProductButton(if (state.formatPendingMode != null) "형식 변경 다시 확인" else if (mode == ListeningMode.BRIEFING) "원문으로 듣기" else "브리핑으로 듣기", {
            onFormat(state.formatPendingMode ?: if (mode == ListeningMode.BRIEFING) ListeningMode.ORIGINAL_FULL else ListeningMode.BRIEFING)
        }, enabled = !state.busy, secondary = true)
        ProductButton("말하기 연습", { send(UiIntent.StartPractice(state.origin)) }, enabled = !state.busy, secondary = true)
        ProductButton("기사에 질문하기", { send(UiIntent.OpenArticleQuestion(state.origin)) }, enabled = !state.busy, secondary = true)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun PreparedOffer(state: ProductScreenState, send: (UiIntent) -> Unit) {
    // Manual/history/cancelled offers never display or reconstruct an eight-second timer.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ProductButton("다음 뉴스 듣기", { send(UiIntent.SelectArticle(state.origin, null)) }, busy = state.busy)
        ProductButton("그만 듣기", { send(UiIntent.Stop(state.origin)) }, enabled = !state.busy, secondary = true)
    }
}

private data class PreparedReaderLine(val key: String, val text: String, val current: Boolean)

@Composable
private fun PreparedReader(state: ProductScreenState, script: SourcePublicScript?, modifier: Modifier) {
    val work = state.session.preparedListening ?: return
    val currentIndex = state.segmentPlayback.snapshot?.takeIf { it.program.owner == state.session.owner && work.accepts(it.program) &&
        it.program.purpose in setOf(SegmentPurpose.MAIN, SegmentPurpose.FOLLOWUP) }?.cursor?.segmentIndex ?: work.cursor.segmentIndex
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        if (script == null) {
            ProductStatus(if (state.readerStatus == LoadStatus.LOADING) "대본을 불러오고 있어요" else "이 대본을 지금 확인할 수 없어요",
                "선택한 기사와 구간은 유지돼요. 다른 대본으로 바꾸지 않아요", loading = state.readerStatus == LoadStatus.LOADING)
        } else key(state.session.owner, script.scriptId, script.textHash) {
            val lines = remember(script.scriptId, script.textHash, script.manifest, currentIndex) {
                val manifest = script.manifest?.takeIf { it.scriptId == script.scriptId.toString() && it.scriptHash == script.textHash &&
                    it.manifestId == work.cursor.manifestId.toString() && it.manifestHash == work.cursor.manifestHash &&
                    it.segmentsVersion == work.cursor.manifestVersion.toLong() }
                val count = script.text.codePointCount(0, script.text.length).toLong()
                if (manifest != null && manifest.segments.all { it.startCp >= 0 && it.endCp > it.startCp && it.endCp <= count }) {
                    manifest.segments.map { segment -> PreparedReaderLine(segment.segmentId,
                        script.text.substring(script.text.offsetByCodePoints(0, segment.startCp.toInt()),
                            script.text.offsetByCodePoints(0, segment.endCp.toInt())), segment.order == currentIndex?.toLong()) }
                } else script.text.split(Regex("\n\\s*\n")).filter { it.isNotBlank() }
                    .mapIndexed { index, text -> PreparedReaderLine("paragraph/$index", text, false) }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 32.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)) {
                itemsIndexed(lines, key = { _, line -> line.key }) { _, line ->
                    Text(line.text, style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().then(if (line.current) Modifier.background(MaterialTheme.colorScheme.primaryContainer) else Modifier)
                            .semantics { if (line.current) stateDescription = "현재 구간" })
                }
            }
        }
    }
}

/** Same centered 88dp transport, with segment actions and actual runtime status. */
@Composable
internal fun PreparedPlaybackDock(state: ProductScreenState, send: (UiIntent) -> Unit) {
    val work = state.session.preparedListening ?: return
    val snapshot = state.segmentPlayback.snapshot?.takeIf { it.program.owner == state.session.owner && work.accepts(it.program) &&
        it.program.purpose in setOf(SegmentPurpose.MAIN, SegmentPurpose.FOLLOWUP) }
    val status = if (snapshot != null) state.segmentPlayback.status else if (state.segmentPlayback.snapshot == null &&
        state.segmentPlayback.status == SegmentRuntimeStatus.UNAVAILABLE) SegmentRuntimeStatus.UNAVAILABLE else SegmentRuntimeStatus.IDLE
    val playing = status == SegmentRuntimeStatus.SPEAKING
    val available = state.canRead && state.mediaAllowed && !state.busy && !state.followupWaiting &&
        work.stage != ListeningStage.ARTICLE_OFFER && snapshot != null && status !in setOf(SegmentRuntimeStatus.UNAVAILABLE, SegmentRuntimeStatus.PREPARING)
    val index = snapshot?.cursor?.segmentIndex ?: work.cursor.segmentIndex
    val count = snapshot?.program?.manifest?.segments?.size
    val transport: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ send(UiIntent.Seek(state.origin, -1)) }, Modifier.size(ProductMetrics.touch), enabled = available && index != null && index > 0) {
                Icon(painterResource(R.drawable.news_rotate_ccw), "이전 구간", Modifier.size(32.dp))
            }
            FilledIconButton({ send(UiIntent.PlayPause(state.origin, !playing)) }, Modifier.size(ProductMetrics.play),
                enabled = playing || available, shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.tertiary, contentColor = MaterialTheme.colorScheme.onTertiary)) {
                Icon(painterResource(if (playing) R.drawable.news_pause else R.drawable.news_play), if (playing) "일시정지" else "재생", Modifier.size(36.dp))
            }
            IconButton({ send(UiIntent.Seek(state.origin, 1)) }, Modifier.size(ProductMetrics.touch), enabled = available && index != null && count != null && index < count - 1) {
                Icon(painterResource(R.drawable.news_rotate_cw), "다음 구간", Modifier.size(32.dp))
            }
        }
    }
    val position: @Composable () -> Unit = {
        Text(if (work.stage == ListeningStage.ARTICLE_OFFER) "기사 종료 선택" else if (index != null && count != null) "구간 ${index + 1} / $count" else "구간을 확인하고 있어요",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val activity: @Composable () -> Unit = {
        Text(when {
            work.stage == ListeningStage.ARTICLE_OFFER -> "다음 행동을 골라 주세요"
            state.followupWaiting -> "후속 듣기 대기"
            status == SegmentRuntimeStatus.SPEAKING -> "낭독 중"
            status == SegmentRuntimeStatus.PREPARING -> "낭독 준비 중"
            status == SegmentRuntimeStatus.UNAVAILABLE -> "이 차량에서 낭독을 사용할 수 없어요"
            status == SegmentRuntimeStatus.PHASE_COMPLETED -> "다음 행동을 골라 주세요"
            snapshot == null -> "낭독 연결을 확인하고 있어요"
            else -> "재생을 기다리고 있어요"
        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        BoxWithConstraints(Modifier.padding(24.dp)) {
            if (maxWidth >= 1050.dp) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { position() }
                transport()
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { activity() }
            } else Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) { transport(); position(); activity() }
        }
    }
}

@Composable
internal fun PreparedSourceMetadata(source: WirePreparationDiscovery) {
    val publishedAt = source.source.publishedAtUtc
    Text("${source.source.publisher} · ${editorialLabel(source.source.editorialType)}", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(if (source.source.timezoneStatus == "verified" && publishedAt != null)
        "원문 게시 · ${publicationTime(publishedAt)}" else "원문 표기 · ${source.source.publishedAtRaw}", style = MaterialTheme.typography.bodySmall)
}

private fun publicationTime(value: String): String = runCatching {
    Instant.parse(value).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm XXX"))
}.getOrDefault(value)

internal fun editorialLabel(value: String): String = when (value) {
    "news" -> "뉴스"
    "analysis" -> "분석 기사"
    "institutional_release" -> "기관 발표"
    else -> "출처 성격 미확인"
}

internal fun publicSourceUri(value: String): URI? = runCatching { URI(value) }.getOrNull()?.takeIf {
    it.scheme in setOf("https", "http") && !it.host.isNullOrBlank() && it.userInfo == null
}

internal fun matchesCurrentSource(source: WirePreparationDiscovery, content: SegmentContentRef, script: SourcePublicScript?): Boolean {
    if (source.articleId != content.articleId.toString() || source.sourceSnapshotId != content.sourceSnapshotId.toString()) return false
    return when (content) {
        is PreparedContentRef -> source.sourceHash == content.sourceHash
        is ContentRef -> (script?.contentRef as? WireSource6LegacyContentRef6)?.legacyRef?.let {
            it.articleId == content.articleId.toString() && it.sourceSnapshotId == content.sourceSnapshotId.toString() &&
                it.bundleId == content.bundleId.toString() && it.listeningScriptId == content.listeningScriptId.toString()
        } == true
    }
}
