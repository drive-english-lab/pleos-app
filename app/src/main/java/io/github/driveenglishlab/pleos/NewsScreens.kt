package io.github.driveenglishlab.pleos

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.driveenglishlab.pleos.domain.*
import io.github.driveenglishlab.pleos.ui.*
import io.github.driveenglishlab.pleos.ui.components.*
import io.github.driveenglishlab.pleos.ui.theme.ProductMetrics
import java.net.URI

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun NewsScreens(state: ProductScreenState, send: (UiIntent) -> Unit, onDiagnostics: () -> Unit,
    onOpenSource: (UiOrigin, URI) -> Unit, onMicrophonePermission: () -> Unit = {}) {
    var discardTarget by remember(state.session.owner) { mutableStateOf<SessionScreen?>(null) }
    var formatTarget by remember(state.session.owner, state.session.listening?.selectionId, state.session.preparedListening?.selectionId) { mutableStateOf<ListeningMode?>(null) }
    val selectionId = state.session.preparedListening?.selectionId ?: state.session.listening?.selectionId
    val practiceModal = state.session.practiceModal?.takeIf { it.visible && it.token.owner == state.session.owner && it.token.selectionId == selectionId && state.canRead &&
        state.session.screen in setOf(SessionScreen.PLAYER, SessionScreen.ARTICLE_OFFER, SessionScreen.QUESTION, SessionScreen.CAPTURE, SessionScreen.FEEDBACK) }
    val editing = state.session.screen in setOf(SessionScreen.ONBOARDING, SessionScreen.SETTINGS)
    val dirty = state.draft?.dirty == true || state.appearanceDraft != state.appearance || state.bandDraft?.let { it != state.learningPreference.band } == true
    fun navigate(destination: SessionScreen) {
        if (practiceModal != null) { send(UiIntent.Navigate(state.origin, destination)); return }
        if (state.session.task != null) { send(UiIntent.FinishPractice(state.origin)); return }
        if (state.session.screen == SessionScreen.ARTICLE_QUESTION) { send(UiIntent.ReturnFromQuestion(state.origin)); return }
        if (editing && dirty && !state.busy) discardTarget = destination
        else send(UiIntent.Navigate(state.origin, destination))
    }
    LaunchedEffect(state.canRead) { if (!state.canRead) { discardTarget = null; formatTarget = null } }
    BackHandler(state.canRead && state.session.screen != SessionScreen.NEWS && state.session.screen != SessionScreen.ONBOARDING) {
        navigate(if ((state.session.listening == null && state.session.preparedListening == null) || state.session.screen in setOf(SessionScreen.PLAYER, SessionScreen.ARTICLE_OFFER))
            SessionScreen.NEWS else SessionScreen.PLAYER)
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ProductHeader(state, onNews = { navigate(SessionScreen.NEWS) }, onHistory = { navigate(SessionScreen.HISTORY) },
            onSettings = { navigate(SessionScreen.SETTINGS) })
        state.notice?.let { ProductNotice(it) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !state.canRead -> Column(Modifier.fillMaxSize()) {
                    IdentityRecoveryNotice(state, allowUnbound = true)
                    ProductStatus("이어서 이용할 준비를 확인해 주세요", state.recovery ?: "차량과 학습 연결을 확인하고 있어요",
                        Modifier.weight(1f), loading = SessionOperationKind.PROFILE in state.session.activeOperations) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (state.deletionPending) ProductButton("삭제 상태 다시 확인", { send(UiIntent.RetryDeletion(state.origin)) })
                            ProductButton("다시 확인", { send(UiIntent.Refresh(state.origin)) }, busy = state.busy)
                            ProductButton("연결 점검", onDiagnostics, secondary = true)
                        }
                    }
                    if (state.session.preparedListening != null) {
                        PreparedPlaybackDock(state, send)
                    } else if (state.session.listening != null) {
                        if (state.mediaAllowed) state.selectedArticle?.title?.let { Text(it, style = MaterialTheme.typography.titleLarge) }
                        PlaybackDock(state, send)
                    }
                }
                state.session.screen in setOf(SessionScreen.ONBOARDING, SessionScreen.SETTINGS) -> SettingsPage(state, send, onDiagnostics)
                practiceModal != null -> if (state.session.preparedListening != null) PreparedPlayerScreen(state, send, onFormat = { formatTarget = it })
                    else PlayerPage(state, send, onFormat = { formatTarget = it })
                state.session.screen in setOf(SessionScreen.QUESTION, SessionScreen.CAPTURE, SessionScreen.FEEDBACK) -> PracticeScreen(state, send, onMicrophonePermission)
                state.session.screen == SessionScreen.ARTICLE_QUESTION -> QuestionScreen(state, send)
                state.session.screen == SessionScreen.NEWS -> NewsPage(state, send, onOpenSource)
                state.session.screen == SessionScreen.ARTICLE_INFO -> ArticlePage(state, send, onOpenSource)
                state.session.screen == SessionScreen.HISTORY || state.session.screen == SessionScreen.SAVED -> ProfileScreen(state, send)
                state.session.screen in setOf(SessionScreen.PLAYER, SessionScreen.ARTICLE_OFFER) ->
                    if (state.session.preparedListening != null) PreparedPlayerScreen(state, send, onFormat = { formatTarget = it })
                    else PlayerPage(state, send, onFormat = { formatTarget = it })
                else -> ProductStatus("학습을 확인하고 있어요", "저장된 단계는 유지돼요. 다음으로 할 일을 골라 주세요") {
                    ProductButton("뉴스 보기", { send(UiIntent.Navigate(state.origin, SessionScreen.NEWS)) })
                }
            }
        }
    }
    if (practiceModal != null) Dialog(onDismissRequest = { send(UiIntent.FinishPractice(state.origin)) },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.widthIn(max = 1440.dp).fillMaxWidth(0.94f).fillMaxHeight(0.94f),
            shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("같은 기사로 말하기", style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).semantics { heading() })
                    ProductButton("닫기", { send(UiIntent.FinishPractice(state.origin)) }, secondary = true)
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { PracticeScreen(state, send, onMicrophonePermission) }
            }
        }
    }
    if (practiceModal == null) ReviewToolSheet(state, send)
    if (discardTarget != null && state.canRead) AlertDialog(onDismissRequest = { discardTarget = null },
        title = { Text("적용하지 않은 설정을 취소할까요?") },
        text = { Text("기존 듣기 설정은 유지돼요") },
        confirmButton = { ProductButton("변경 취소", {
            val destination = discardTarget; discardTarget = null
            send(UiIntent.CancelSettings(state.origin, destination))
        }) }, dismissButton = { ProductButton("계속 편집", { discardTarget = null }, secondary = true) })
    formatTarget?.takeIf { state.canRead }?.let { target ->
        AlertDialog(onDismissRequest = { formatTarget = null }, title = { Text("지금 기사 형식을 바꿀까요?") },
            text = { Text("듣던 위치는 저장돼요. 같은 기사를 바꾼 형식으로 처음부터 들어요. 다음 기사 설정은 유지돼요") },
            confirmButton = { ProductButton("처음부터 듣기", { formatTarget = null; send(UiIntent.ChangeFormat(state.origin, target)) }) },
            dismissButton = { ProductButton("취소", { formatTarget = null }, secondary = true) })
    }
}

@Composable
private fun ProductHeader(state: ProductScreenState, onNews: () -> Unit, onHistory: () -> Unit, onSettings: () -> Unit) {
    val navigation = state.canRead && state.session.screen != SessionScreen.ONBOARDING
    val brand: @Composable () -> Unit = {
        Column { Text("Drive English", style = MaterialTheme.typography.titleLarge)
            Text(state.profileLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    val settings: @Composable () -> Unit = {
        if (navigation) IconButton(onSettings, Modifier.size(ProductMetrics.touch)) {
            Icon(painterResource(R.drawable.news_settings), "듣기 설정", Modifier.size(32.dp))
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 900.dp) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            brand(); Spacer(Modifier.weight(1f))
            if (navigation) { ProductButton("뉴스", onNews, secondary = true); ProductButton("기록", onHistory, secondary = true) }
            settings()
        } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { brand(); Spacer(Modifier.weight(1f)); settings() }
            if (navigation) Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                ProductButton("뉴스", onNews, secondary = true); ProductButton("기록", onHistory, secondary = true)
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ListeningSelectionControls(state: ProductScreenState, send: (UiIntent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val target = state.pendingOriginalTarget
        val title = target?.let { pending ->
            state.catalogViews.items.firstOrNull { it.key.articleId.toString() == pending.articleId && it.key.sourceSnapshotId.toString() == pending.sourceSnapshotId }?.title
                ?: state.preparedCatalog.firstOrNull { it.articleId == pending.articleId && it.sourceSnapshotId == pending.sourceSnapshotId }?.title
        }
        if (state.selectionPending && title != null) Text("준비 중인 기사 · $title", style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val uncertain = state.playbackRequest3.pendingChoiceRetention?.reconciliation == io.github.driveenglishlab.pleos.data.PendingChoiceReconciliation.SAME_CHILD_REQUIRED
            val canContinue = PendingChoiceKind.CONTINUE in state.supportedPendingChoices
            val expired = state.activePlaybackRequest?.state != PlaybackRequestState.ACTIVE || state.playbackRequest3.retainedParentAnchor != null &&
                state.playbackRequest3.response?.outcomeProjection?.state == "no_candidate"
            ProductButton(if (!state.selectionPending) "새 뉴스 듣기" else if (uncertain || !canContinue) "선택 상태 확인"
                else if (expired) "다시 기다리기" else "준비 상태 확인", {
                send(if (state.selectionPending) UiIntent.RetrySelection(state.origin) else UiIntent.SelectArticle(state.origin, null, true))
            }, enabled = !state.busy, busy = state.busy)
            if (state.session.listening != null || state.session.preparedListening != null)
                ProductButton("이어서 듣기", { send(UiIntent.Resume(state.origin)) }, enabled = !state.busy, secondary = true)
            if (state.selectionPending) {
                if (state.originalAlternativeAvailable) ProductButton("선택한 기사 원문 듣기", { send(UiIntent.PendingOriginal(state.origin)) }, enabled = !state.busy, secondary = true)
                if (PendingChoiceKind.READY_ALTERNATIVE in state.supportedPendingChoices)
                    ProductButton("준비된 다른 뉴스", { send(UiIntent.ReadyAlternative(state.origin)) }, enabled = !state.busy, secondary = true)
                ProductButton("선택 취소", { send(UiIntent.CancelSelection(state.origin)) }, secondary = true)
            }
        }
        state.noCandidateReason?.let { reason -> ProductNotice(when (reason) {
            "all_listened" -> "준비된 뉴스를 모두 들었어요. 원하는 기사를 직접 골라 다시 들을 수 있어요"
            "visited_in_stream" -> "이번에 고른 기사만 남아 있어요. 새 뉴스를 시작하거나 기사를 직접 골라 주세요"
            "original_unavailable" -> "선택한 기사의 원문을 지금 들을 수 없어요. 듣던 기록과 설정은 유지돼요"
            "ready_alternative_unavailable" -> "지금 바로 들을 다른 뉴스가 없어요. 같은 기사에서 다시 기다리거나 원문을 확인할 수 있어요"
            else -> "선택한 관심 분야에서 이용할 새 뉴스가 없어요"
        }) }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun NewsPage(state: ProductScreenState, send: (UiIntent) -> Unit, openSource: (UiOrigin, URI) -> Unit) {
    if (state.catalogViews.owner != null && state.catalogViews.owner == state.session.owner) {
        CatalogViewsPanel(state, send, openSource)
        return
    }
    val preparedCatalog = state.categoryConfig != null || state.preparedCatalog.isNotEmpty()
    val empty = if (preparedCatalog) state.preparedCatalog.isEmpty() else state.catalog.isEmpty()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text("관심 있는 소식부터 들어보세요", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        ListeningSelectionControls(state, send)
        when {
            state.catalogStatus == LoadStatus.LOADING && empty ->
                ProductStatus("뉴스를 불러오고 있어요", "준비된 기사와 출처를 확인하고 있어요", loading = true)
            state.catalogStatus == LoadStatus.FAILED && empty -> ProductStatus("뉴스를 불러오지 못했어요", "저장한 진행은 유지돼요. 연결을 확인한 뒤 다시 요청해 주세요") {
                ProductButton("다시 확인", { send(UiIntent.Refresh(state.origin)) })
            }
            empty -> ProductStatus("이 분야에 이용할 뉴스가 없어요", "관심 분야를 확인하거나 나중에 새 뉴스를 확인해 주세요")
            else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (preparedCatalog) items(state.preparedCatalog, key = { "${it.articleId}/${it.sourceSnapshotId}" }) { article ->
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(article.title, style = MaterialTheme.typography.titleLarge)
                            PreparedSourceMetadata(article)
                            val duration = if (state.draft?.original?.listeningMode == "briefing") article.briefingEstimatedDurationMs else article.originalEstimatedDurationMs
                            Text(duration?.takeIf { it > 0 }?.let { "예상 듣기 길이 · 약 ${(it - 1) / 60_000 + 1}분" } ?: "듣기 길이는 아직 확인되지 않았어요",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                ProductButton("이 기사 듣기", { send(UiIntent.SelectArticle(state.origin, java.util.UUID.fromString(article.articleId))) }, enabled = !state.busy, secondary = true)
                                publicSourceUri(article.source.originalUrl)?.let { uri ->
                                    ProductButton("원문 보기", { openSource(state.origin, uri) }, secondary = true)
                                }
                            }
                        }
                    }
                } else items(state.catalog, key = { "${it.articleId}/${it.bundleId}" }) { article ->
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(article.title, style = MaterialTheme.typography.titleLarge)
                            Text("${article.publisher} · 약 ${(article.originalDurationMs + 59_999) / 60_000}분", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(if (article.originalTimeVerified) "원문 게시 · ${article.originalPublishedAt}" else "원문 표기 · ${article.publishedAtRaw}",
                                style = MaterialTheme.typography.bodySmall)
                            ProductButton("이 기사 듣기", { send(UiIntent.SelectArticle(state.origin, article.articleId)) }, enabled = !state.busy, secondary = true)
                        }
                    }
                }
                if (state.moreCatalog) item {
                    ProductButton("기사 더 보기", { send(UiIntent.MoreCatalog(state.origin)) }, busy = state.catalogStatus == LoadStatus.LOADING, secondary = true)
                }
            }
        }
    }
}

@Composable
private fun PlayerPage(state: ProductScreenState, send: (UiIntent) -> Unit, onFormat: (ListeningMode) -> Unit) {
    val work = state.session.listening ?: return
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(state.selectedArticle?.title ?: "기사 내용을 확인하고 있어요", style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() })
        Text(when (work.stage) {
            ListeningStage.FOLLOWUP -> "후속 듣기"
            ListeningStage.ARTICLE_OFFER -> "기사 재생이 끝났어요"
            else -> if (work.content.listeningMode == ListeningMode.BRIEFING) "짧은 영어 브리핑" else "영어 원문 전체"
        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.learningMaterial.frozen?.takeIf { it.selectionId == work.selectionId && it.content == work.content }?.let { frozen ->
            Text("이번 연습 단계 · ${bandLabel(frozen.selectedBand)}", style = MaterialTheme.typography.bodySmall)
            val source = frozen.view.material.sourceLevel
            Text(if (source.status == "verified") "원문 자료 수준 · ${source.band ?: "미확인"}" else "원문 자료 수준은 아직 확인되지 않았어요", style = MaterialTheme.typography.bodySmall)
            if (!frozen.canPractice) ProductNotice("영어 듣기는 가능해요. 이 단계의 말하기 질문을 준비하고 있어요")
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            val wide = maxWidth >= 1200.dp
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(if (wide) 2.25f else 1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (!wide) PlayerActions(state, send, onFormat, compact = true)
                    NewsReader(state, Modifier.weight(1f).fillMaxWidth())
                }
                if (wide) Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    PlayerActions(state, send, onFormat)
                    if (work.stage != ListeningStage.ARTICLE_OFFER) Text("기사 질문은 차량에서 확인된 입력 경로로 이용해요", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (work.stage == ListeningStage.ARTICLE_OFFER) OfferActions(state, send, compact = true)
        PlaybackDock(state, send)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun PlayerActions(state: ProductScreenState, send: (UiIntent) -> Unit, onFormat: (ListeningMode) -> Unit, compact: Boolean = false) {
    val mode = state.session.listening?.content?.listeningMode
    val actions: @Composable () -> Unit = {
        ProductButton(if (state.listenOnlyActive) "말하기 제안 다시 받기" else "오늘은 듣기만", { send(UiIntent.ListeningOnly(state.origin, !state.listenOnlyActive)) }, secondary = true)
        ProductButton("기사 정보", { send(UiIntent.Navigate(state.origin, SessionScreen.ARTICLE_INFO)) }, secondary = true)
        ProductButton(if (state.formatPendingMode != null) "형식 변경 다시 확인" else if (mode == ListeningMode.BRIEFING) "원문으로 듣기" else "브리핑으로 듣기", {
            onFormat(state.formatPendingMode ?: if (mode == ListeningMode.BRIEFING) ListeningMode.ORIGINAL_FULL else ListeningMode.BRIEFING)
        }, enabled = !state.busy, secondary = true)
        ProductButton("말하기 연습", { send(UiIntent.StartPractice(state.origin)) }, enabled = !state.busy, secondary = true)
        ProductButton("기사에 질문하기", { send(UiIntent.OpenArticleQuestion(state.origin)) }, enabled = !state.busy, secondary = true)
    }
    if (compact) FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { actions() }
    else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { actions() }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun OfferActions(state: ProductScreenState, send: (UiIntent) -> Unit, compact: Boolean = false) {
    if (!compact) Text("다음으로 무엇을 할까요?", style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!state.listenOnlyActive) ProductButton("말하기 연습", { send(UiIntent.StartPractice(state.origin)) }, enabled = !state.busy)
        ProductButton("다음 기사 듣기", { send(UiIntent.SelectArticle(state.origin, null)) }, busy = state.busy)
        ProductButton("그만 듣기", { send(UiIntent.Stop(state.origin)) }, secondary = true)
        ProductButton("뉴스 보기", { send(UiIntent.Navigate(state.origin, SessionScreen.NEWS)) }, secondary = true)
    }
}

@Composable
private fun ArticlePage(state: ProductScreenState, send: (UiIntent) -> Unit, openSource: (UiOrigin, URI) -> Unit) {
    val preparedWork = state.session.preparedListening
    val prepared = state.preparedSource?.takeIf { preparedWork != null && matchesCurrentSource(it, preparedWork.content, state.preparedScript) }
    val preparedScript = state.preparedScript?.takeIf { it.scriptId == state.session.preparedListening?.cursor?.scriptId &&
        it.textHash == state.session.preparedListening?.cursor?.scriptHash }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text("기사 정보", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            if (prepared != null) {
                item { Text(prepared.title, style = MaterialTheme.typography.titleLarge) }
                item { PreparedSourceMetadata(prepared) }
                item { Text("원문 제공 · ${editorialLabel(prepared.source.editorialType)}", style = MaterialTheme.typography.bodyMedium) }
                item { Text(prepared.source.originalUrl, style = MaterialTheme.typography.bodySmall) }
                item { publicSourceUri(prepared.source.originalUrl)?.let { uri ->
                    ProductButton("원문 열기", { openSource(state.origin, uri) }, secondary = true)
                } ?: Text("이 원문 링크를 열 수 없어요", style = MaterialTheme.typography.bodySmall) }
            } else if (state.session.preparedListening == null) {
                state.selectedArticle?.let { article ->
                item { Text(article.title, style = MaterialTheme.typography.titleLarge) }
                item { Text("매체 · ${article.publisher}", style = MaterialTheme.typography.bodyMedium) }
                item { Text(if (article.originalTimeVerified) "원문 게시 · ${article.originalPublishedAt}" else "원문 게시 표기 · ${article.publishedAtRaw}", style = MaterialTheme.typography.bodySmall) }
                item { Text("앱에 준비된 시각 · ${article.publishedAt}", style = MaterialTheme.typography.bodySmall) }
                item { Text(article.originalUrl.toString(), style = MaterialTheme.typography.bodySmall) }
                item { ProductButton("원문 열기", { openSource(state.origin, article.originalUrl) }, secondary = true) }
                } ?: item { Text("현재 듣는 기사의 출처를 확인하고 있어요", style = MaterialTheme.typography.bodyMedium) }
            } else item { Text("선택한 기사의 출처를 지금 확인할 수 없어요. 기사와 듣던 구간은 유지돼요", style = MaterialTheme.typography.bodyMedium) }
            if (prepared != null) preparedScript?.let { item { Text(it.text, style = MaterialTheme.typography.bodyLarge) } }
            else if (state.session.preparedListening == null) state.reader?.let { item { Text(it.script.text, style = MaterialTheme.typography.bodyLarge) } }
        }
        ProductButton("재생으로 돌아가기", { send(UiIntent.Navigate(state.origin, SessionScreen.PLAYER)) })
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun SettingsPage(state: ProductScreenState, send: (UiIntent) -> Unit, diagnostics: () -> Unit) {
    val draft = state.draft ?: return
    var deleteConfirmation by remember(state.session.owner) { mutableStateOf(false) }
    var logoutConfirmation by remember(state.session.owner) { mutableStateOf(false) }
    val onboarding = !draft.original.onboardingComplete
    val categories = state.categoryConfig?.categories?.takeIf { it.size == 6 && it.map { category -> category.id }.distinct().size == 6 &&
        it.map { category -> category.displayOrder }.toSet() == (0L..5L).toSet() }?.sortedBy { it.displayOrder }.orEmpty()
    val topics = categories.filter { it.active }
    val retainedTopics = draft.topics.filter { selected -> topics.none { it.id == selected } }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(if (onboarding) "어떤 뉴스를 들을까요?" else "듣기 설정", style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() })
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text("관심 분야", style = MaterialTheme.typography.titleMedium) }
            if (categories.isEmpty()) item {
                ProductNotice("관심 분야 설정을 확인하지 못했어요. 기존 선택은 유지돼요")
                ProductButton("관심 분야 다시 확인", { send(UiIntent.Refresh(state.origin)) }, busy = state.busy, secondary = true)
            }
            items(topics, key = { "topic/${it.id}" }) { topic ->
                ChoiceRow(topic.displayName, topic.id in draft.topics, { send(UiIntent.Topic(state.origin, topic.id)) }, multiple = true,
                    description = topic.definition, enabled = !state.busy)
            }
            items(retainedTopics, key = { "retained/$it" }) { topic ->
                ChoiceRow(categories.firstOrNull { it.id == topic }?.displayName ?: topic, true, {}, multiple = true, enabled = false,
                    description = if (categories.isEmpty()) "분야 설정을 확인할 때까지 기존 선택을 유지해요"
                    else "기존 선택을 유지하고 있어요. 현재 선택할 수 있는 관심 분야에는 없어요")
            }
            item { Text("영어 듣기 형식", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp)) }
            item { ChoiceRow("영어 원문 전체", draft.listeningMode == "original_full", { send(UiIntent.Mode(state.origin, "original_full")) }, enabled = !state.busy) }
            item { ChoiceRow("짧은 영어 브리핑", draft.listeningMode == "briefing", { send(UiIntent.Mode(state.origin, "briefing")) }, enabled = !state.busy) }
            item { Text("영어 기사 뒤에 들을 내용", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp)) }
            items(listOf("none" to "추가 없이 듣기", "en_summary" to "영어 요약", "ko_summary" to "한국어 요약", "ko_translation" to "한국어 전체 번역")) { (value, label) ->
                ChoiceRow(label, draft.followup == value, { send(UiIntent.FollowupChoice(state.origin, value)) },
                    description = if (value == "en_summary" && draft.listeningMode == "briefing") "브리핑을 들은 뒤에는 생략해요" else null, enabled = !state.busy)
            }
            item { Text("다음 기사의 연습 단계", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp)) }
            items(LearningBand.entries) { band ->
                ChoiceRow(bandLabel(band), (state.bandDraft ?: state.learningPreference.band) == band, { send(UiIntent.Band(state.origin, band)) },
                    description = "다음 새 기사부터 적용해요. 현재 기사와 연습은 유지돼요", enabled = !state.busy)
            }
            if (state.learningPreference.pending) item { ProductNotice("단계 선택을 기기에 저장했어요. 서버 동기화를 기다리고 있어요") }
            if (state.learningPreference.conflict) item { ProductNotice("다른 곳에서 단계가 바뀌었어요. 다음 기사 전에 현재 선택을 다시 확인해 주세요") }
            item { RecommendationSection(state, send) }
            item { Text("화면 밝기", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp)) }
            items(DeviceAppearance.entries) { theme ->
                ChoiceRow(when (theme) { DeviceAppearance.SYSTEM -> "기기 설정에 맞추기"; DeviceAppearance.LIGHT -> "밝게"; DeviceAppearance.DARK -> "어둡게" },
                    state.appearanceDraft == theme, { send(UiIntent.Appearance(state.origin, theme)) }, enabled = !state.busy)
            }
            item {
                ProductButton("설정 미리듣기", { send(UiIntent.PreviewSettings(state.origin)) },
                    enabled = !state.busy && draft.followup != null, secondary = true)
                Text("선택한 듣기 방식과 연습 단계의 일부를 확인해요. 설정 적용과는 별개예요", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!onboarding) item {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("학습 기록과 계정", style = MaterialTheme.typography.titleMedium)
                    Text("계정을 연결하면 이 차량의 설정과 진행 중 학습을 우선하고, 양쪽 기록은 함께 보존해요. 로그인 연결은 차량과 제공자 설정 확인이 필요해요", style = MaterialTheme.typography.bodySmall)
                    ProductButton("계정 연결 준비 중", {}, enabled = false, secondary = true)
                    ProductButton("학습 기록 삭제", { deleteConfirmation = true }, enabled = !state.busy, secondary = true)
                    ProductButton("이 차량에서 로그아웃", { logoutConfirmation = true }, enabled = !state.busy, secondary = true)
                }
            }
            item { Text("로그인 없이도 이 차량의 학습 기록을 이어갈 수 있어요", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ProductButton(if (state.settingsPending) "설정 다시 확인" else "설정 적용", { send(UiIntent.ApplySettings(state.origin)) },
                enabled = state.settingsPending || categories.isNotEmpty() && draft.valid && (draft.dirty || !draft.original.onboardingComplete || state.appearanceDraft != state.appearance || state.bandDraft?.let { it != state.learningPreference.band } == true || state.learningPreference.pending || state.learningPreference.conflict), busy = state.busy)
            if (onboarding) ProductButton("연결 점검", diagnostics, secondary = true)
            else ProductButton("취소", { send(UiIntent.CancelSettings(state.origin)) }, enabled = !state.busy, secondary = true)
        }
        if (!draft.valid) Text(if (draft.topics.isEmpty()) "관심 분야를 하나 이상 골라 주세요" else "기사 뒤에 들을 내용을 골라 주세요",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (deleteConfirmation && state.canRead) AlertDialog(onDismissRequest = { deleteConfirmation = false },
        title = { Text("학습 기록을 삭제할까요?") },
        text = { Text("현재 사용자 프로필의 모든 기기에서 듣기·연습 기록, 답변과 피드백, 이어하기를 삭제해요. 로그인과 관심 설정은 유지돼요") },
        confirmButton = { ProductButton("학습 기록 삭제", { deleteConfirmation = false; send(UiIntent.DeleteHistory(state.origin)) }) },
        dismissButton = { ProductButton("취소", { deleteConfirmation = false }, secondary = true) })
    if (logoutConfirmation && state.canRead) AlertDialog(onDismissRequest = { logoutConfirmation = false },
        title = { Text("이 차량에서 로그아웃할까요?") },
        text = { Text("다른 기기의 로그인과 학습 기록은 유지해요. 이 차량은 새 미로그인 프로필로 바뀌어요") },
        confirmButton = { ProductButton("로그아웃", { logoutConfirmation = false; send(UiIntent.Logout(state.origin)) }) },
        dismissButton = { ProductButton("취소", { logoutConfirmation = false }, secondary = true) })

}


private fun bandLabel(value: LearningBand): String = when(value) {
    LearningBand.BASIC -> "기초 · A1–A2"
    LearningBand.INTERMEDIATE -> "중급 · B1–B2"
    LearningBand.ADVANCED -> "고급 · C1–C2"
}

@Composable
private fun RecommendationSection(state: ProductScreenState, send: (UiIntent) -> Unit) {
    val recommendation = state.learningRecommendation
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("내 답변을 참고한 단계 추천", style = MaterialTheme.typography.titleMedium)
        Text("선택한 단계, 기사의 실제 수준, 내 답변을 참고한 추천은 서로 달라요", style = MaterialTheme.typography.bodySmall)
        val view = recommendation.view
        Text(when {
            recommendation.loading -> "추천 근거를 확인하고 있어요"
            recommendation.failed -> "추천을 지금 확인하지 못했어요. 선택한 단계는 유지돼요"
            view?.status == "insufficient_evidence" -> "참고할 영어 답변 기록이 더 필요해요"
            view?.status == "pending" -> "참고할 답변을 검토하고 있어요"
            view?.status == "deferred" -> "지금 기록만으로 단계를 추천하기 어려워요. 선택한 단계를 계속 사용할 수 있어요"
            view?.status == "unavailable" -> "단계 추천을 준비하고 있어요"
            view?.status == "stale" -> "이전 추천이에요. 현재 기록을 다시 확인해 주세요"
            view?.status == "kept" -> "현재 단계를 유지하기로 했어요"
            view?.status == "dismissed" -> "이 추천을 닫았어요. 같은 근거로 다시 권하지 않아요"
            view?.status == "applied" -> "선택한 추천을 다음 기사에 적용했어요"
            view?.status == "ready" -> view.recommendation?.reasonKo ?: "답변을 참고한 추천을 확인할 수 있어요"
            else -> "원할 때 추천을 확인할 수 있어요"
        }, style = MaterialTheme.typography.bodySmall)
        recommendation.evaluatedAt?.let { Text("원래 평가일 · $it", style = MaterialTheme.typography.bodySmall) }
        if (recommendation.maySuggest) {
            val band = view?.recommendation?.recommendedBand?.let { LearningBand.valueOf(it.uppercase(java.util.Locale.ROOT)) }
            if (band != null) ProductButton("${bandLabel(band)} 다음 기사에 적용", { send(UiIntent.Recommendation(state.origin, RecommendationAction.APPLY)) })
            ProductButton("현재 단계 유지", { send(UiIntent.Recommendation(state.origin, RecommendationAction.KEEP)) }, secondary = true)
            ProductButton("추천 닫기", { send(UiIntent.Recommendation(state.origin, RecommendationAction.DISMISS)) }, secondary = true)
        }
        ProductButton("추천 상태 확인", { send(UiIntent.ReadRecommendation(state.origin)) }, enabled = !recommendation.loading && !recommendation.pendingAction, secondary = true)
    }
}
