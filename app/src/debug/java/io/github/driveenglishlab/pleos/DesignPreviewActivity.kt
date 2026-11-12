package io.github.driveenglishlab.pleos

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import io.github.driveenglishlab.pleos.platform.vehicle.DrivingAccess
import io.github.driveenglishlab.pleos.platform.vehicle.DrivingAccessSource
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.text.style.TextOverflow
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONArray
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.draw.rotate

/** Debug-only visual preview. Release keeps MainActivity and the setup workflow. */
class DesignPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var theme by rememberSaveable { mutableStateOf("system") }
            PreviewTheme(theme) {
                NewsDesignPreview(theme = theme, onTheme = { theme = it }, onDiagnostics = {
                    startActivity(Intent(this, MainActivity::class.java))
                })
            }
        }
    }
}

internal data class PreviewCue(val startMs: Int, val endMs: Int, val text: String)
internal data class PreviewStory(
    val id: String, val title: String, val summary: String, val createdAt: String,
    val prompt: String, val promptKo: String, val answer: String, val translation: String,
    val audio: String, val seconds: Double, val cues: List<PreviewCue>,
    val qa: String, val learner: String, val before: String, val after: String, val feedback: String, val summaryKo: String,
)

private fun loadPreviewStories(context: Context): List<PreviewStory> {
    val data = JSONArray(context.assets.open("preview-stories.json").bufferedReader().use { it.readText() })
    return List(data.length()) { index ->
        val item = data.getJSONObject(index)
        val cues = item.getJSONArray("cues")
        PreviewStory(
            item.getString("id"), item.getString("title"), item.getString("summary"),
            item.getString("createdAt"), item.getString("prompt"), item.getString("promptKo"),
            item.getString("answer"), item.getString("translation"), item.getString("audio"),
            item.getDouble("seconds"), List(cues.length()) { cueIndex ->
                val cue = cues.getJSONObject(cueIndex)
                PreviewCue((cue.getDouble("start") * 1000).toInt(), (cue.getDouble("end") * 1000).toInt(), cue.getString("text"))
            },
            item.getString("qa"), item.getString("learner"), item.getString("before"), item.getString("after"), item.getString("feedback"), item.getString("summaryKo"),
        )
    }
}

private val LocalPreviewDark = staticCompositionLocalOf { false }

@OptIn(ExperimentalTextApi::class)
@Composable
private fun PreviewTheme(mode: String, content: @Composable () -> Unit) {
    val family = remember { FontFamily(listOf(400, 500, 600).map { weight ->
        Font(R.font.asta_sans, weight = FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))
    }) }
    fun text(size: Int, line: Int, weight: Int = 400) = TextStyle(fontFamily = family, fontSize = size.sp, lineHeight = line.sp, fontWeight = FontWeight(weight))
    val typography = Typography(
        headlineSmall = text(36, 46, 600), titleLarge = text(32, 40, 600), titleMedium = text(26, 34, 600),
        titleSmall = text(22, 30, 500), bodyLarge = text(32, 50), bodyMedium = text(24, 36), bodySmall = text(20, 28),
        labelLarge = text(22, 28, 500), labelMedium = text(18, 24, 500), labelSmall = text(16, 22),
    )
    val light = lightColorScheme(primary = Color(0xFF1B64DA), onPrimary = Color.White,
        primaryContainer = Color(0xFFE8F3FF), onPrimaryContainer = Color(0xFF1B64DA),
        surface = Color(0xFFF7F8FA), onSurface = Color(0xFF191F28), surfaceVariant = Color(0xFFF2F4F6),
        onSurfaceVariant = Color(0xFF4E5968), surfaceContainerLowest = Color.White, outlineVariant = Color(0xFFD9DFE7))
    val dark = darkColorScheme(primary = Color(0xFF79ADFF), onPrimary = Color(0xFF10213B),
        primaryContainer = Color(0xFF22395B), onPrimaryContainer = Color(0xFF9BC1FF),
        surface = Color(0xFF171B22), onSurface = Color(0xFFF4F6F9), surfaceVariant = Color(0xFF2A3341),
        onSurfaceVariant = Color(0xFFAEB8C5), surfaceContainerLowest = Color(0xFF202630), outlineVariant = Color(0xFF3A4451))
    val darkMode = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    CompositionLocalProvider(LocalPreviewDark provides darkMode) {
        MaterialTheme(colorScheme = if (darkMode) dark else light, typography = typography, content = content)
    }
}

@Composable
private fun NewsDesignPreview(theme: String, onTheme: (String) -> Unit, onDiagnostics: () -> Unit) {
    val context = LocalContext.current
    val stories = remember { loadPreviewStories(context) }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var screen by rememberSaveable { mutableStateOf("player") }
    var translated by rememberSaveable(selected) { mutableStateOf(false) }
    var following by rememberSaveable(selected) { mutableStateOf(true) }
    var practiceStage by rememberSaveable(selected) { mutableStateOf("question") }
    var sampleCase by rememberSaveable { mutableStateOf("expression") }
    var attempts by rememberSaveable(selected) { mutableIntStateOf(0) }
    var qaPlaying by remember { mutableStateOf(false) }
    var qaPosition by remember { mutableIntStateOf(0) }
    var clearHistory by remember { mutableStateOf(false) }
    val positions = remember { mutableStateMapOf<String, Int>() }
    val practiced = remember { mutableStateMapOf<String, Boolean>() }
    val story = stories[selected]
    val audio = remember { PreviewPlayback(context.applicationContext, stories, story.id) }
    val vehicle = remember { if (Build.VERSION.SDK_INT >= 34) DrivingAccessSource(context.applicationContext) else null }
    val unavailable = remember { MutableStateFlow(DrivingAccess(status = "unsupported")) }
    val vehicleState = if (Build.VERSION.SDK_INT >= 34) vehicle?.state ?: unavailable else unavailable
    // Safety routes close even while another vehicle Activity is covering this one.
    val access by vehicleState.collectAsState()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { if (Build.VERSION.SDK_INT >= 34) vehicle?.refresh() }
    var qaStopGeneration by remember { mutableStateOf(0L) }
    LaunchedEffect(audio.mediaId) {
        val index = stories.indexOfFirst { it.id == audio.mediaId }
        if (index >= 0 && selected != index) { selected = index; practiceStage = "question"; attempts = 0 }
    }
    LaunchedEffect(access.detailsAllowed) { if (!access.detailsAllowed) { screen = "player"; clearHistory = false } }
    LaunchedEffect(access.componentAllowed) { if (access.componentAllowed == false) audio.stop() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val list = rememberLazyListState()
    val current = story.cues.indexOfLast { audio.positionMs >= it.startMs }.coerceAtLeast(0)
    LaunchedEffect(audio, lifecycle, story.id) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) { audio.refresh(); if (audio.ready && audio.positionMs > 0) positions[story.id] = audio.positionMs; delay(200) }
        }
    }
    // Language changes reset reading once; playback never resets the Korean reading position.
    LaunchedEffect(translated, story.id) { list.scrollToItem(0) }
    LaunchedEffect(current / 2, following, translated) {
        if (following && !translated) {
            val info = list.layoutInfo
            val target = info.visibleItemsInfo.find { it.index == current / 2 }
            if (target == null || target.offset < info.viewportStartOffset || target.offset + target.size > info.viewportEndOffset) list.scrollToItem(current / 2)
        }
    }
    LaunchedEffect(practiceStage) {
        if (practiceStage == "processing") {
            delay(700)
            practiceStage = when (sampleCase) { "unclear" -> "unclear"; "error" -> "error"; else -> "feedback" }
        }
    }
    DisposableEffect(lifecycle, audio, vehicle) {
        if (Build.VERSION.SDK_INT >= 34) vehicle?.start()
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME && Build.VERSION.SDK_INT >= 34) vehicle?.start() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); if (Build.VERSION.SDK_INT >= 34) vehicle?.close(); audio.close() }
    }
    fun navigate(destination: String) { if (!access.detailsAllowed && destination != "player") return; if (destination != "player") audio.pause(); screen = destination }
    fun choose(index: Int) { audio.load(stories[index], positions[stories[index].id] ?: 0); screen = "player"; practiceStage = "question"; attempts = 0 }
    fun openPractice() { if (practiceStage == "done") { practiceStage = "question"; attempts = 0 }; navigate("practice") }
    fun openQa() { qaPlaying = audio.playing; qaPosition = audio.positionMs; qaStopGeneration = audio.stopGeneration; navigate("qa") }
    fun closeQa() { screen = "player"; if (qaPlaying && qaStopGeneration == audio.stopGeneration && access.detailsAllowed) { audio.seek((qaPosition - 5000).coerceAtLeast(0)); audio.resume() }; qaPlaying = false }
    BackHandler(enabled = access.detailsAllowed && screen != "player") { if (screen == "qa") closeQa() else screen = "player" }
    Surface(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp)) {
            val wide = maxWidth >= 1100.dp
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column {
                        Text("Drive English", style = MaterialTheme.typography.titleLarge)
                        Text(if (access.detailsAllowed) "창작 기사 시연" else access.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.weight(1f))
                    if (access.detailsAllowed) {
                    TextButton(onClick = { navigate("news") }, modifier = Modifier.heightIn(min = 48.dp)) { Text("뉴스") }
                    TextButton(onClick = ::openPractice, modifier = Modifier.heightIn(min = 48.dp)) { Text("연습") }
                    TextButton(onClick = { navigate("history") }, modifier = Modifier.heightIn(min = 48.dp)) { Text("기록") }
                    IconButton(onClick = { navigate("settings") }, modifier = Modifier.size(56.dp)) { PreviewIcon(R.drawable.preview_settings, "듣기 설정") }
                    }
                }
                Box(Modifier.weight(1f)) {
                    if (!access.detailsAllowed) DrivingPlayer(story, audio, access, onPermission = {
                        permission.launch(arrayOf("android.car.permission.CAR_SPEED", "android.car.permission.CAR_POWERTRAIN"))
                    }) else when (screen) {
                        "news" -> NativeNewsList(stories, selected, onChoose = ::choose, onBack = { screen = "player" })
                        "article" -> NativeArticle(story, onBack = { screen = "player" }, onPractice = ::openPractice)
                        "practice" -> NativePractice(story, practiceStage, sampleCase, attempts, onAttempts = { attempts = it }, onCase = { sampleCase = it }, onStage = { practiceStage = it },
                            onDone = { practiced[story.id] = true; practiceStage = "done" }, onBack = { screen = "player" })
                        "history" -> NativeHistory(stories, positions, practiced, onChoose = ::choose, onNews = { navigate("news") }, onPractice = { index -> choose(index); openPractice() }, onBack = { screen = "player" })
                        "settings" -> NativeSettings(theme, onApply = { onTheme(it); screen = "player" }, onClear = { clearHistory = true }, onDiagnostics = onDiagnostics, onBack = { screen = "player" })
                        "qa" -> NativeQuestion(story, onBack = ::closeQa)
                        else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                NativeReader(story, translated, current, list, onManualScroll = { following = false },
                                    audio = audio, modifier = Modifier.weight(if (wide) 2.25f else 1f),
                                    compactActions = if (!wide) { { PreviewActionRow { NativePracticeButton(::openPractice); TextButton(onClick = { navigate("article") }) { Text("기사 정보") }; TextButton(onClick = ::openQa) { Text("기사 내용 질문") } } } } else null)
                                if (wide) Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                    if (audio.ready && audio.positionMs >= audio.durationMs - 100 && !audio.playing) {
                                        Text("다음으로 무엇을 할까요?", style = MaterialTheme.typography.titleMedium)
                                        Text(if (selected < stories.lastIndex) "같은 기사로 연습하거나 다음 뉴스를 들어보세요." else "준비된 뉴스를 모두 들었어요.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        NativePracticeButton(::openPractice, Modifier.fillMaxWidth())
                                        if (selected < stories.lastIndex) OutlinedButton(onClick = { choose(selected + 1) }, modifier = Modifier.heightIn(min = 56.dp)) { Text("다음 뉴스 듣기") }
                                        TextButton(onClick = { audio.seek(0); if (!audio.playing) audio.toggle() }) { Text("처음부터 듣기") }
                                    } else {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("다음 기사", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                            TextButton(onClick = { navigate("news") }) { Text("전체 보기") }
                                        }
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                        stories.forEachIndexed { index, item -> if (index != selected) {
                                            TextButton(onClick = { choose(index) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                                                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    Text(item.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                                                    Text(item.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                        } }
                                        Spacer(Modifier.weight(1f))
                                        NativePracticeButton(::openPractice, Modifier.fillMaxWidth())
                                        TextButton(onClick = { navigate("article") }) { Text("기사 정보") }
                                        TextButton(onClick = ::openQa) { Text("기사 내용 질문") }
                                    }
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            NativeDock(audio, translated, following, current, story.cues.size, onLanguage = { translated = it; following = true }, onFollow = { following = !following })
                        }
                    }
                }
            }
        }
    }
    if (clearHistory && access.detailsAllowed) AlertDialog(onDismissRequest = { clearHistory = false }, title = { Text("시연 기록을 지울까요?") },
        text = { Text("현재 실행에서 만든 예시 기록만 지워요. 실제 계정이나 서버 기록은 사용하지 않아요.", style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = { positions.clear(); practiced.clear(); audio.seek(0); clearHistory = false }) { Text("시연 기록 지우기") } },
        dismissButton = { TextButton(onClick = { clearHistory = false }) { Text("취소") } })
}

@Composable
private fun NativeReader(story: PreviewStory, translated: Boolean, current: Int, list: androidx.compose.foundation.lazy.LazyListState,
                         onManualScroll: () -> Unit, audio: PreviewPlayback, modifier: Modifier, compactActions: (@Composable () -> Unit)?) {
    val groups = remember(story.id) { story.cues.chunked(2) }
    val manualScroll = remember(onManualScroll) { object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset { if (source == NestedScrollSource.UserInput && available.y != 0f) onManualScroll(); return Offset.Zero }
    } }
    Surface(modifier.fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = RoundedCornerShape(16.dp)) {
        Column {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
                val stack = maxWidth < 900.dp || LocalDensity.current.fontScale >= 1.2f
                val metadata: @Composable () -> Unit = { Text("원문 낭독 · 창작 기사 · ${story.createdAt}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (stack) { Text(story.title, style = MaterialTheme.typography.titleLarge); metadata() }
                    else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text(story.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f)); metadata()
                    }
                    compactActions?.invoke()
                    if (!audio.ready || audio.error.isNotEmpty()) PreviewActionRow {
                        Text(audio.error.ifEmpty { "오디오를 준비하고 있어요." }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (audio.error.isNotEmpty()) TextButton(onClick = { audio.load(story, audio.positionMs) }) { Text("다시 불러오기") }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth().nestedScroll(manualScroll).semantics { contentDescription = if (translated) "한국어 번역 본문" else "영어 대본 본문" },
                verticalArrangement = Arrangement.spacedBy(32.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(32.dp)) {
                if (translated) items(story.translation.split(Regex("(?<=[.!?])\\s+")).chunked(2)) { paragraph -> Text(paragraph.joinToString(" "), style = MaterialTheme.typography.bodyLarge) }
                else items(groups.size) { groupIndex ->
                    Text(buildAnnotatedString { groups[groupIndex].forEachIndexed { sentenceIndex, cue ->
                        val active = groupIndex * 2 + sentenceIndex == current
                        withStyle(SpanStyle(color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                            background = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)) { append(cue.text) }; append(" ")
                    } }, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun NativeDock(audio: PreviewPlayback, translated: Boolean, following: Boolean, current: Int, total: Int, onLanguage: (Boolean) -> Unit, onFollow: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 1200.dp || LocalDensity.current.fontScale >= 1.2f
        val twoRows = maxWidth < 900.dp
        val progress: @Composable (Modifier) -> Unit = { modifier -> NativeTimeline(audio, modifier) }
        val reading: @Composable (Modifier) -> Unit = { modifier -> NativeReadingTools(translated, following, current, total, compact, onLanguage, onFollow, modifier) }
        if (twoRows) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) { progress(Modifier.weight(1f)); reading(Modifier.weight(1f)) }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Transport(audio) }
        } else Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            progress(Modifier.weight(1f)); Transport(audio); reading(Modifier.weight(1f))
        }
    }
}

@Composable
private fun NativeTimeline(audio: PreviewPlayback, modifier: Modifier) {
    var scrub by remember { mutableStateOf<Float?>(null) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Slider(value = scrub ?: audio.positionMs.toFloat(), onValueChange = { scrub = it }, valueRange = 0f..audio.durationMs.toFloat(),
            onValueChangeFinished = { scrub?.let { audio.seek(it.toInt()) }; scrub = null }, enabled = audio.ready,
            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary, inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant, activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
            modifier = Modifier.weight(1f).semantics { contentDescription = "재생 위치" })
        Text("${timeLabel(audio.positionMs)} / ${timeLabel(audio.durationMs)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NativeReadingTools(translated: Boolean, following: Boolean, current: Int, total: Int, compact: Boolean, onLanguage: (Boolean) -> Unit, onFollow: () -> Unit, modifier: Modifier) {
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        PreviewActionRow {
            if (compact) Box {
                TextButton(onClick = { menu = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (translated) "한국어 번역" else "영어 대본"); PreviewIcon(R.drawable.preview_chevron_right, null, Modifier.rotate(90f)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("영어 대본") }, onClick = { onLanguage(false); menu = false })
                    DropdownMenuItem(text = { Text("한국어 번역") }, onClick = { onLanguage(true); menu = false })
                }
            } else {
                TextButton(onClick = { onLanguage(false) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("영어 대본", color = if (!translated) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                TextButton(onClick = { onLanguage(true) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("한국어 번역", color = if (translated) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (!translated) {
                if (!compact) Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) { Text("${current + 1} / $total", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                TextButton(onClick = onFollow, shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.textButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                    modifier = Modifier.heightIn(min = 48.dp).semantics { stateDescription = if (following) "켜짐" else "꺼짐" }) { Text(if (following) "자동 스크롤" else "현재 문장으로", style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

@Composable
private fun Transport(audio: PreviewPlayback, driving: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SeekButton(R.drawable.preview_rotate_ccw, "10초 전으로", audio.ready, if (driving) 76 else 56) { audio.seek(audio.positionMs - 10000) }
        FilledIconButton(onClick = audio::toggle, enabled = audio.ready && audio.canPlay, modifier = Modifier.size(if (driving) 88.dp else 72.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (LocalPreviewDark.current) Color(0xFF79ADFF) else Color(0xFF3182F6), contentColor = MaterialTheme.colorScheme.onPrimary)) {
            PreviewIcon(if (audio.playing) R.drawable.preview_pause else R.drawable.preview_play, if (audio.playing) "일시 정지" else "재생", Modifier.size(32.dp))
        }
        SeekButton(R.drawable.preview_rotate_cw, "10초 앞으로", audio.ready, if (driving) 76 else 56) { audio.seek(audio.positionMs + 10000) }
    }
}

@Composable
private fun SeekButton(icon: Int, label: String, enabled: Boolean, target: Int = 56, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(target.dp).semantics { contentDescription = label }) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { PreviewIcon(icon, null, Modifier.size(28.dp)); Text("10", style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
internal fun NativePracticeButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClick, modifier = modifier.heightIn(min = 64.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
        PreviewIcon(R.drawable.preview_mic, null); Spacer(Modifier.width(12.dp)); Text("말하기 연습")
    }
}

@Composable
internal fun PreviewIcon(resource: Int, label: String?, modifier: Modifier = Modifier) { Icon(painter = painterResource(resource), contentDescription = label, modifier = modifier.size(28.dp)) }

internal fun timeLabel(millis: Int): String { val seconds = millis.coerceAtLeast(0) / 1000; return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}" }

@Composable
private fun DrivingPlayer(story: PreviewStory, audio: PreviewPlayback, access: DrivingAccess, onPermission: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = RoundedCornerShape(16.dp)) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(32.dp)) {
            val compact = maxWidth < 456.dp
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Text(story.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (access.uxKnown && access.requiresOptimization == true) "대본과 연습은 주차 후 이용할 수 있어요." else "차량 상태를 확인하면 대본과 연습을 열 수 있어요.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (access.mayRequestPermission) Button(onClick = onPermission, modifier = Modifier.heightIn(min = 76.dp)) { Text("차량 상태 접근 허용") }
                    if (audio.error.isNotEmpty()) {
                        Text(audio.error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        Button(onClick = { audio.load(story, audio.positionMs) }, modifier = Modifier.heightIn(min = 76.dp)) { Text("다시 불러오기") }
                    }
                    if (access.componentAllowed == false) Text("현재 차량에서 오디오를 사용할 수 없어요.", style = MaterialTheme.typography.bodyMedium)
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LinearProgressIndicator(progress = { audio.positionMs.toFloat() / audio.durationMs.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "청취 진행" })
                    Text("${timeLabel(audio.positionMs)} / ${timeLabel(audio.durationMs)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val previous: @Composable () -> Unit = { IconButton(onClick = audio::previous, enabled = audio.ready && audio.canPrevious, modifier = Modifier.size(76.dp)) { PreviewIcon(R.drawable.preview_skip_back, "이전 기사") } }
                val next: @Composable () -> Unit = { IconButton(onClick = audio::next, enabled = audio.ready && audio.canNext, modifier = Modifier.size(76.dp)) { PreviewIcon(R.drawable.preview_skip_forward, "다음 기사") } }
                if (compact) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Transport(audio, driving = true) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { previous(); Spacer(Modifier.width(24.dp)); next() }
                } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    previous(); Spacer(Modifier.width(24.dp)); Transport(audio, driving = true); Spacer(Modifier.width(24.dp)); next()
                }
            }
        }
    }
}
