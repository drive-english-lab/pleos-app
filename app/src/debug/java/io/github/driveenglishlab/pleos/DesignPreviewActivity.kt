package io.github.driveenglishlab.pleos

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import java.io.Closeable

/** Debug-only visual preview. Release keeps MainActivity and the setup workflow. */
class DesignPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PreviewTheme {
                NewsDesignPreview(onDiagnostics = {
                    startActivity(Intent(this, MainActivity::class.java))
                })
            }
        }
    }
}

private data class PreviewCue(val startMs: Int, val endMs: Int, val text: String)
private data class PreviewStory(
    val id: String, val title: String, val summary: String, val createdAt: String,
    val prompt: String, val promptKo: String, val answer: String, val translation: String,
    val audio: String, val seconds: Double, val cues: List<PreviewCue>,
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
        )
    }
}

/** Foreground-only sample playback: focus loss and leaving the activity always pause. */
private class PreviewAudio(private val context: Context) : Closeable {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener { if (it != AudioManager.AUDIOFOCUS_GAIN) pause() }.build()
    private var media: MediaPlayer? = null
    private var seeking = false
    var ready by mutableStateOf(false); private set
    var playing by mutableStateOf(false); private set
    var positionMs by mutableIntStateOf(0); private set
    var durationMs by mutableIntStateOf(1); private set
    var error by mutableStateOf(""); private set

    fun load(story: PreviewStory, resumeMs: Int) {
        pause()
        media?.release()
        media = null
        ready = false; seeking = false; error = ""
        positionMs = resumeMs; durationMs = (story.seconds * 1000).toInt()
        val candidate = MediaPlayer()
        media = candidate
        try {
            candidate.setAudioAttributes(attributes)
            context.assets.openFd(story.audio).use { fd -> candidate.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
            candidate.setOnPreparedListener {
                if (media === it) {
                    durationMs = it.duration.coerceAtLeast(1)
                    ready = true
                    seek(resumeMs)
                }
            }
            candidate.setOnSeekCompleteListener { if (media === it) { seeking = false; positionMs = it.currentPosition } }
            candidate.setOnCompletionListener {
                if (media === it) { playing = false; positionMs = durationMs; manager.abandonAudioFocusRequest(focus) }
            }
            candidate.setOnErrorListener { _, _, _ ->
                if (media === candidate) { pause(); ready = false; error = "샘플 오디오를 불러오지 못했어요." }
                true
            }
            candidate.prepareAsync()
        } catch (_: Exception) {
            candidate.release(); media = null; error = "샘플 오디오를 불러오지 못했어요."
        }
    }

    fun toggle() {
        val current = media ?: return
        if (!ready) return
        if (playing) { pause(); return }
        if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            error = "다른 오디오가 끝난 뒤 다시 재생해 주세요."
            return
        }
        if (positionMs >= durationMs - 100) seek(0)
        current.start(); playing = true; error = ""
    }

    fun seek(position: Int) {
        if (!ready) return
        positionMs = position.coerceIn(0, durationMs)
        seeking = true
        media?.seekTo(positionMs.toLong(), MediaPlayer.SEEK_CLOSEST)
    }

    fun refresh() { if (ready && !seeking) positionMs = media?.currentPosition ?: positionMs }
    fun pause() {
        if (playing) { media?.pause(); playing = false }
        manager.abandonAudioFocusRequest(focus)
    }
    override fun close() { pause(); media?.release(); media = null; ready = false }
}

@OptIn(ExperimentalTextApi::class)
@Composable
private fun PreviewTheme(content: @Composable () -> Unit) {
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
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dark else light, typography = typography, content = content)
}

@Composable
private fun NewsDesignPreview(onDiagnostics: () -> Unit) {
    val context = LocalContext.current
    val stories = remember { loadPreviewStories(context) }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var screen by rememberSaveable { mutableStateOf("player") }
    var resumePosition by rememberSaveable(selected) { mutableIntStateOf(0) }
    var showSource by remember { mutableStateOf(false) }
    val story = stories[selected]
    val audio = remember { PreviewAudio(context.applicationContext) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(story.id) { audio.load(story, resumePosition) }
    LaunchedEffect(audio, lifecycle, story.id) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) { audio.refresh(); if (audio.ready) resumePosition = audio.positionMs; delay(200) }
        }
    }
    DisposableEffect(lifecycle, audio) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) audio.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); audio.close() }
    }
    BackHandler(enabled = screen != "player") { screen = "player" }
    fun openPractice() { audio.pause(); screen = "practice" }
    fun choose(index: Int) { audio.pause(); selected = index; screen = "player" }
    Surface(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
            val wide = maxWidth >= 1100.dp
            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column {
                        Text("Drive English", style = MaterialTheme.typography.titleLarge)
                        Text("주차용 디자인 시연", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { audio.pause(); screen = "news" }) { Text("뉴스") }
                    TextButton(onClick = ::openPractice) { Text("연습") }
                    IconButton(onClick = { audio.pause(); onDiagnostics() }, modifier = Modifier.size(56.dp)) {
                        PreviewIcon(R.drawable.preview_settings, "연결 점검")
                    }
                }
                when (screen) {
                    "news" -> NewsList(stories, onChoose = ::choose, onBack = { screen = "player" })
                    "practice" -> PracticePreview(story, onBack = { screen = "player" })
                    else -> Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        TranscriptPlayer(story, audio, onRetry = { audio.load(story, audio.positionMs) },
                            modifier = Modifier.weight(if (wide) 2.25f else 1f),
                            compactActions = if (!wide) { { Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                PracticeButton(onClick = ::openPractice)
                                TextButton(onClick = { showSource = true }) { Text("기사 정보") }
                                TextButton(onClick = { audio.pause(); screen = "news" }) { Text("기사 목록") }
                            } } } else null)
                        if (wide) Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("다음 기사", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                TextButton(onClick = { audio.pause(); screen = "news" }) { Text("전체 보기") }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            stories.forEachIndexed { index, item -> if (index != selected) {
                                TextButton(onClick = { choose(index) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(item.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                                        Text(item.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            } }
                            Spacer(Modifier.weight(1f))
                            PracticeButton(onClick = ::openPractice, modifier = Modifier.fillMaxWidth())
                            TextButton(onClick = { showSource = true }) { PreviewIcon(R.drawable.preview_info, null); Spacer(Modifier.width(8.dp)); Text("기사 정보") }
                        }
                    }
                }
            }
        }
    }
    if (showSource) AlertDialog(onDismissRequest = { showSource = false }, title = { Text("기사 정보") },
        text = { Text("Drive English에서 작성한 창작 시연 기사예요. 실제 보도 내용이 아니며 외부 원문 링크는 없어요.\n\n게시일 ${story.createdAt}", style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = { showSource = false }) { Text("닫기") } })
}

@Composable
private fun TranscriptPlayer(story: PreviewStory, audio: PreviewAudio, onRetry: () -> Unit, modifier: Modifier, compactActions: (@Composable () -> Unit)?) {
    var translated by rememberSaveable(story.id) { mutableStateOf(false) }
    var following by rememberSaveable(story.id) { mutableStateOf(true) }
    var scrub by remember(story.id) { mutableStateOf<Float?>(null) }
    val list = rememberLazyListState()
    val current = story.cues.indexOfLast { audio.positionMs >= it.startMs }.coerceAtLeast(0)
    val groups = remember(story.id) { story.cues.chunked(2) }
    val manualScroll = remember { object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && available.y != 0f) following = false
            return Offset.Zero
        }
    } }
    LaunchedEffect(current / 2, following, translated) {
        if (following && !translated) {
            val info = list.layoutInfo
            val target = info.visibleItemsInfo.find { it.index == current / 2 }
            if (target == null || target.offset < info.viewportStartOffset || target.offset + target.size > info.viewportEndOffset) list.scrollToItem(current / 2)
        } else if (translated) list.scrollToItem(0)
    }
    Surface(modifier.fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = RoundedCornerShape(16.dp)) {
        Column {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(24.dp)) {
                val inline = maxWidth >= 900.dp && LocalDensity.current.fontScale < 1.2f
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(story.title, style = MaterialTheme.typography.titleLarge)
                            Text("원문 낭독 · 창작 기사 · ${story.createdAt}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Transport(audio)
                    }
                    val tools: @Composable (Modifier) -> Unit = { toolModifier ->
                        Row(toolModifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { translated = false; following = true }) { Text("영어 대본", color = if (!translated) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant) }
                            TextButton(onClick = { translated = true }) { Text("한국어 번역", color = if (translated) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant) }
                            Spacer(Modifier.weight(1f))
                            if (!translated) {
                                Text("${current + 1} / ${story.cues.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = { following = !following }, shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.textButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                                    modifier = Modifier.semantics { stateDescription = if (following) "켜짐" else "꺼짐" }) {
                                    Text(if (following) "자동 스크롤" else "현재 문장으로", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                    val timeline: @Composable (Modifier) -> Unit = { timelineModifier ->
                        Row(timelineModifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Slider(value = scrub ?: audio.positionMs.toFloat(), onValueChange = { scrub = it }, valueRange = 0f..audio.durationMs.toFloat(),
                                onValueChangeFinished = { scrub?.let { audio.seek(it.toInt()) }; scrub = null }, enabled = audio.ready,
                                colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary, inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant, activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
                                modifier = Modifier.weight(1f).semantics { contentDescription = "재생 위치" })
                            Text("${timeLabel(audio.positionMs)} / ${timeLabel(audio.durationMs)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (inline) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) { tools(Modifier.weight(1.7f)); timeline(Modifier.weight(1f)) }
                    else { tools(Modifier.fillMaxWidth()); timeline(Modifier.fillMaxWidth()) }
                    compactActions?.invoke()
                    if (!audio.ready || audio.error.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(audio.error.ifEmpty { "오디오를 준비하고 있어요." }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        if (audio.error.isNotEmpty()) TextButton(onClick = onRetry) { Text("다시 불러오기") }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth().nestedScroll(manualScroll),
                verticalArrangement = Arrangement.spacedBy(32.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(32.dp)) {
                if (translated) items(story.translation.split(Regex("(?<=[.!?])\\s+")).chunked(2)) { paragraph -> Text(paragraph.joinToString(" "), style = MaterialTheme.typography.bodyLarge) }
                else items(groups.size) { groupIndex ->
                    Text(buildAnnotatedString {
                        groups[groupIndex].forEachIndexed { sentenceIndex, cue ->
                            val active = groupIndex * 2 + sentenceIndex == current
                            withStyle(SpanStyle(color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                background = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)) { append(cue.text) }
                            append(" ")
                        }
                    }, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun Transport(audio: PreviewAudio) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SeekButton(R.drawable.preview_rotate_ccw, "10초 전으로", audio.ready) { audio.seek(audio.positionMs - 10000) }
        FilledIconButton(onClick = audio::toggle, enabled = audio.ready, modifier = Modifier.size(72.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (isSystemInDarkTheme()) Color(0xFF79ADFF) else Color(0xFF3182F6), contentColor = MaterialTheme.colorScheme.onPrimary)) {
            PreviewIcon(if (audio.playing) R.drawable.preview_pause else R.drawable.preview_play, if (audio.playing) "일시 정지" else "재생", Modifier.size(32.dp))
        }
        SeekButton(R.drawable.preview_rotate_cw, "10초 앞으로", audio.ready) { audio.seek(audio.positionMs + 10000) }
    }
}

@Composable
private fun SeekButton(icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(56.dp).semantics { contentDescription = label }) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { PreviewIcon(icon, null, Modifier.size(28.dp)); Text("10", style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
private fun PracticeButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClick, modifier = modifier.height(64.dp), shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
        PreviewIcon(R.drawable.preview_mic, null); Spacer(Modifier.width(12.dp)); Text("말하기 연습")
    }
}

@Composable
private fun NewsList(stories: List<PreviewStory>, onChoose: (Int) -> Unit, onBack: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        TextButton(onClick = onBack) { PreviewIcon(R.drawable.preview_arrow_left, null); Spacer(Modifier.width(8.dp)); Text("재생으로 돌아가기") }
        Text("관심 뉴스", style = MaterialTheme.typography.headlineSmall)
        Text("창작 시연 기사 ${stories.size}편", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) { items(stories.size) { index ->
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                TextButton(onClick = { onChoose(index) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stories[index].title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text(stories[index].summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    PreviewIcon(R.drawable.preview_chevron_right, null)
                }
            }
        } }
    }
}

@Composable
private fun PracticePreview(story: PreviewStory, onBack: () -> Unit) {
    var example by rememberSaveable(story.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        TextButton(onClick = onBack) { PreviewIcon(R.drawable.preview_arrow_left, null); Spacer(Modifier.width(8.dp)); Text("재생으로 돌아가기") }
        Text(story.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(story.prompt, style = MaterialTheme.typography.headlineSmall)
        Text(story.promptKo, style = MaterialTheme.typography.bodyMedium)
        Text("예시 답변으로 연습 흐름을 확인하는 시연 화면이에요. 실제 음성 입력은 사용하지 않아요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { example = !example }) { Text(if (example) "답변 예시 닫기" else "답변 예시 보기") }
        if (example) Text(story.answer, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun PreviewIcon(resource: Int, label: String?, modifier: Modifier = Modifier) {
    Icon(painter = painterResource(resource), contentDescription = label, modifier = modifier.size(28.dp))
}

private fun timeLabel(millis: Int): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
