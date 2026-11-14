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
import io.github.driveenglishlab.pleos.domain.*
import io.github.driveenglishlab.pleos.ui.*
import io.github.driveenglishlab.pleos.ui.components.*
import io.github.driveenglishlab.pleos.ui.theme.ProductMetrics
import java.net.URI

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun NewsScreens(state: ProductScreenState, send: (UiIntent) -> Unit, onDiagnostics: () -> Unit,
    onOpenSource: (UiOrigin, URI) -> Unit) {
    var discardTarget by remember(state.session.owner) { mutableStateOf<SessionScreen?>(null) }
    var formatTarget by remember(state.session.owner, state.session.listening?.selectionId) { mutableStateOf<ListeningMode?>(null) }
    val editing = state.session.screen in setOf(SessionScreen.ONBOARDING, SessionScreen.SETTINGS)
    val dirty = state.draft?.dirty == true || state.appearanceDraft != state.appearance
    fun navigate(destination: SessionScreen) {
        if (editing && dirty && !state.busy) discardTarget = destination
        else send(UiIntent.Navigate(state.origin, destination))
    }
    LaunchedEffect(state.canRead) { if (!state.canRead) { discardTarget = null; formatTarget = null } }
    BackHandler(state.canRead && state.session.screen != SessionScreen.NEWS && state.session.screen != SessionScreen.ONBOARDING) {
        navigate(if (state.session.listening == null || state.session.screen in setOf(SessionScreen.PLAYER, SessionScreen.ARTICLE_OFFER))
            SessionScreen.NEWS else SessionScreen.PLAYER)
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ProductHeader(state, onNews = { navigate(SessionScreen.NEWS) }, onHistory = { navigate(SessionScreen.HISTORY) },
            onSettings = { navigate(SessionScreen.SETTINGS) })
        state.notice?.let { ProductNotice(it) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !state.canRead -> Column(Modifier.fillMaxSize()) {
                    ProductStatus("이어서 이용할 준비를 확인해 주세요", state.recovery ?: "차량과 학습 연결을 확인하고 있어요",
                        Modifier.weight(1f), loading = SessionOperationKind.PROFILE in state.session.activeOperations) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ProductButton("다시 확인", { send(UiIntent.Refresh(state.origin)) }, busy = state.busy)
                            ProductButton("연결 점검", onDiagnostics, secondary = true)
                        }
                    }
                    if (state.session.listening != null) {
                        if (state.mediaAllowed) state.selectedArticle?.title?.let { Text(it, style = MaterialTheme.typography.titleLarge) }
                        PlaybackDock(state, send)
                    }
                }
                state.session.screen in setOf(SessionScreen.ONBOARDING, SessionScreen.SETTINGS) -> SettingsPage(state, send, onDiagnostics)
                state.session.screen == SessionScreen.NEWS -> NewsPage(state, send)
                state.session.screen == SessionScreen.ARTICLE_INFO -> ArticlePage(state, send, onOpenSource)
                state.session.screen == SessionScreen.HISTORY || state.session.screen == SessionScreen.SAVED ->
                    ProductStatus("이어서 할 학습", if (state.session.listening != null) "듣던 기사의 위치를 이어갈 수 있어요" else "학습 기록 보기를 준비하고 있어요") {
                        if (state.session.listening != null) ProductButton("이어서 듣기", { send(UiIntent.Resume(state.origin)) })
                        ProductButton("뉴스 보기", { send(UiIntent.Navigate(state.origin, SessionScreen.NEWS)) }, secondary = true)
                    }
                state.session.screen in setOf(SessionScreen.PLAYER, SessionScreen.ARTICLE_OFFER) ->
                    PlayerPage(state, send, onFormat = { formatTarget = it })
                else -> ProductStatus("학습을 확인하고 있어요", "저장된 단계는 유지돼요. 다음으로 할 일을 골라 주세요") {
                    ProductButton("뉴스 보기", { send(UiIntent.Navigate(state.origin, SessionScreen.NEWS)) })
                }
            }
        }
    }
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
private fun NewsPage(state: ProductScreenState, send: (UiIntent) -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text("관심 있는 소식부터 들어보세요", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ProductButton(if (state.selectionPending) "기사 선택 다시 확인" else "새 뉴스 듣기", {
                send(if (state.selectionPending) UiIntent.RetrySelection(state.origin) else UiIntent.SelectArticle(state.origin, null, true))
            }, enabled = !state.busy, busy = state.busy)
            if (state.session.listening != null) ProductButton("이어서 듣기", { send(UiIntent.Resume(state.origin)) }, secondary = true)
        }
        if (state.noCandidateReason != null) ProductNotice(when (state.noCandidateReason) {
            "all_listened" -> "준비된 뉴스를 모두 들었어요. 원하는 기사를 직접 골라 다시 들을 수 있어요"
            "visited_in_stream" -> "이번에 고른 기사만 남아 있어요. 새 뉴스를 시작하거나 기사를 직접 골라 주세요"
            else -> "선택한 관심 분야의 뉴스를 준비하고 있어요"
        })
        when {
            state.catalogStatus == LoadStatus.LOADING && state.catalog.isEmpty() ->
                ProductStatus("뉴스를 불러오고 있어요", "준비된 기사와 출처를 확인하고 있어요", loading = true)
            state.catalogStatus == LoadStatus.FAILED && state.catalog.isEmpty() -> ProductStatus("뉴스를 불러오지 못했어요", "저장한 진행은 유지돼요. 연결을 확인한 뒤 다시 요청해 주세요") {
                ProductButton("다시 확인", { send(UiIntent.Refresh(state.origin)) })
            }
            state.catalog.isEmpty() -> ProductStatus("뉴스를 준비하고 있어요", "아직 준비된 기사가 없어요. 잠시 후 다시 확인해 주세요")
            else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(state.catalog, key = { "${it.articleId}/${it.bundleId}" }) { article ->
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
        BoxWithConstraints(Modifier.weight(1f)) {
            val wide = maxWidth >= 1200.dp
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(if (wide) 2.25f else 1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (!wide) PlayerActions(state, send, onFormat, compact = true)
                    NewsReader(state, Modifier.weight(1f).fillMaxWidth())
                }
                if (wide) Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    PlayerActions(state, send, onFormat)
                    if (work.stage != ListeningStage.ARTICLE_OFFER) Text("같은 기사로 말하기 연습을 준비하고 있어요", style = MaterialTheme.typography.bodySmall,
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
        ProductButton("기사 정보", { send(UiIntent.Navigate(state.origin, SessionScreen.ARTICLE_INFO)) }, secondary = true)
        ProductButton(if (state.formatPendingMode != null) "형식 변경 다시 확인" else if (mode == ListeningMode.BRIEFING) "원문으로 듣기" else "브리핑으로 듣기", {
            onFormat(state.formatPendingMode ?: if (mode == ListeningMode.BRIEFING) ListeningMode.ORIGINAL_FULL else ListeningMode.BRIEFING)
        }, enabled = !state.busy, secondary = true)
        ProductButton("말하기 연습", {}, enabled = false, secondary = true)
    }
    if (compact) FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { actions() }
    else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { actions() }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun OfferActions(state: ProductScreenState, send: (UiIntent) -> Unit, compact: Boolean = false) {
    if (!compact) Text("다음으로 무엇을 할까요?", style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ProductButton("다음 기사 듣기", { send(UiIntent.SelectArticle(state.origin, null)) }, busy = state.busy)
        ProductButton("그만 듣기", { send(UiIntent.Stop(state.origin)) }, secondary = true)
        ProductButton("뉴스 보기", { send(UiIntent.Navigate(state.origin, SessionScreen.NEWS)) }, secondary = true)
    }
}

@Composable
private fun ArticlePage(state: ProductScreenState, send: (UiIntent) -> Unit, openSource: (UiOrigin, URI) -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text("기사 정보", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            state.selectedArticle?.let { article ->
                item { Text(article.title, style = MaterialTheme.typography.titleLarge) }
                item { Text("매체 · ${article.publisher}", style = MaterialTheme.typography.bodyMedium) }
                item { Text(if (article.originalTimeVerified) "원문 게시 · ${article.originalPublishedAt}" else "원문 게시 표기 · ${article.publishedAtRaw}", style = MaterialTheme.typography.bodySmall) }
                item { Text("앱에 준비된 시각 · ${article.publishedAt}", style = MaterialTheme.typography.bodySmall) }
                item { Text(article.originalUrl.toString(), style = MaterialTheme.typography.bodySmall) }
                item { ProductButton("원문 열기", { openSource(state.origin, article.originalUrl) }, secondary = true) }
            } ?: item { Text("현재 듣는 기사의 출처를 확인하고 있어요", style = MaterialTheme.typography.bodyMedium) }
            state.reader?.let { item { Text(it.script.text, style = MaterialTheme.typography.bodyLarge) } }
        }
        ProductButton("재생으로 돌아가기", { send(UiIntent.Navigate(state.origin, SessionScreen.PLAYER)) })
    }
}

private val topicNames = linkedMapOf("technology" to "기술", "science" to "과학", "business" to "경제", "world" to "세계",
    "environment" to "환경", "culture" to "문화")

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun SettingsPage(state: ProductScreenState, send: (UiIntent) -> Unit, diagnostics: () -> Unit) {
    val draft = state.draft ?: return
    val onboarding = !draft.original.onboardingComplete
    val topics = (draft.topics + state.catalog.flatMap { it.topics } + topicNames.keys).sorted()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(if (onboarding) "어떤 뉴스를 들을까요?" else "듣기 설정", style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() })
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text("관심 분야", style = MaterialTheme.typography.titleMedium) }
            items(topics, key = { "topic/$it" }) { topic ->
                ChoiceRow(topicNames[topic] ?: topic, topic in draft.topics, { send(UiIntent.Topic(state.origin, topic)) }, multiple = true,
                    enabled = !state.busy && (topic in draft.topics || draft.topics.size < 20))
            }
            item { Text("영어 듣기 형식", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp)) }
            item { ChoiceRow("영어 원문 전체", draft.listeningMode == "original_full", { send(UiIntent.Mode(state.origin, "original_full")) }, enabled = !state.busy) }
            item { ChoiceRow("짧은 영어 브리핑", draft.listeningMode == "briefing", { send(UiIntent.Mode(state.origin, "briefing")) }, enabled = !state.busy) }
            item { Text("영어 기사 뒤에 들을 내용", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp)) }
            items(listOf("none" to "추가 없이 듣기", "en_summary" to "영어 요약", "ko_summary" to "한국어 요약", "ko_translation" to "한국어 전체 번역")) { (value, label) ->
                ChoiceRow(label, draft.followup == value, { send(UiIntent.FollowupChoice(state.origin, value)) },
                    description = if (value == "en_summary" && draft.listeningMode == "briefing") "브리핑을 들은 뒤에는 생략해요" else null, enabled = !state.busy)
            }
            item { Text("화면 밝기", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp)) }
            items(DeviceAppearance.entries) { theme ->
                ChoiceRow(when (theme) { DeviceAppearance.SYSTEM -> "기기 설정에 맞추기"; DeviceAppearance.LIGHT -> "밝게"; DeviceAppearance.DARK -> "어둡게" },
                    state.appearanceDraft == theme, { send(UiIntent.Appearance(state.origin, theme)) }, enabled = !state.busy)
            }
            item { Text("로그인 없이도 이 차량의 학습 기록을 이어갈 수 있어요", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ProductButton(if (state.settingsPending) "설정 다시 확인" else "설정 적용", { send(UiIntent.ApplySettings(state.origin)) },
                enabled = state.settingsPending || draft.valid && (draft.dirty || !draft.original.onboardingComplete || state.appearanceDraft != state.appearance), busy = state.busy)
            if (onboarding) ProductButton("연결 점검", diagnostics, secondary = true)
            else ProductButton("취소", { send(UiIntent.CancelSettings(state.origin)) }, enabled = !state.busy, secondary = true)
        }
        if (!draft.valid) Text(if (draft.topics.isEmpty()) "관심 분야를 하나 이상 골라 주세요" else "기사 뒤에 들을 내용을 골라 주세요",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
