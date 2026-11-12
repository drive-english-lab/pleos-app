package io.github.driveenglishlab.pleos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Debug views use in-memory, explicitly disclosed sample state only. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PreviewActionRow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun PreviewPage(title: String, subtitle: String, onBack: () -> Unit, actions: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = RoundedCornerShape(16.dp)) {
        Column {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { PreviewIcon(R.drawable.preview_arrow_left, null); Spacer(Modifier.width(8.dp)); Text("재생으로 돌아가기") }
                Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(24.dp)) {
                item { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) { content() } }
            }
            if (actions != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) { PreviewActionRow { actions() } }
            }
        }
    }
}

@Composable
private fun PreviewPrimary(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 56.dp), shape = RoundedCornerShape(12.dp)) { Text(label) }
}

@Composable
private fun PreviewSecondary(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.heightIn(min = 56.dp), shape = RoundedCornerShape(12.dp)) { Text(label) }
}

@Composable
internal fun NativeNewsList(stories: List<PreviewStory>, selected: Int, onChoose: (Int) -> Unit, onBack: () -> Unit) {
    PreviewPage("관심 뉴스", "창작 시연 기사 ${stories.size}편 · 영어 원문 낭독", onBack) {
        stories.forEachIndexed { index, story ->
            TextButton(onClick = { onChoose(index) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.weight(1f).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(story.title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text(story.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("창작 기사 · ${story.createdAt} · ${timeLabel((story.seconds * 1000).toInt())}${if (index == selected) " · 현재 기사" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                PreviewIcon(R.drawable.preview_chevron_right, null)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
internal fun NativeArticle(story: PreviewStory, onBack: () -> Unit, onPractice: () -> Unit) {
    PreviewPage(story.title, "기사 정보 · ${story.createdAt}", onBack, actions = { NativePracticeButton(onPractice) }) {
        Text("출처 및 이용 안내", style = MaterialTheme.typography.titleMedium)
        Text("Drive English에서 작성한 창작 시연 기사예요. 실제 보도 내용이 아니며 외부 원문 링크는 없어요.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(max = 1060.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        story.cues.chunked(2).forEach { paragraph -> Text(paragraph.joinToString(" ") { it.text }, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.widthIn(max = 1060.dp)) }
    }
}

@Composable
internal fun NativePractice(story: PreviewStory, stage: String, sampleCase: String, attempts: Int, onAttempts: (Int) -> Unit, onCase: (String) -> Unit, onStage: (String) -> Unit, onDone: () -> Unit, onBack: () -> Unit) {
    var hint by rememberSaveable(story.id) { mutableStateOf(false) }
    var meaning by rememberSaveable(story.id) { mutableStateOf(false) }
    var example by rememberSaveable(story.id) { mutableStateOf(false) }
    var caseMenu by remember { mutableStateOf(false) }
    var helpExpanded by rememberSaveable(story.id, stage) { mutableStateOf(false) }
    val cases = listOf("expression" to "표현 교정", "good" to "충분한 답변", "fact" to "사실 오류", "unclear" to "인식 불확실", "error" to "통신 오류")
    val enough = sampleCase == "good" || (sampleCase == "expression" && attempts > 1)
    val answer = when { sampleCase == "fact" -> "The plan has already been completed."; enough -> story.answer; else -> story.learner }
    PreviewPage("말하기 연습", story.title, onBack, actions = {
        when (stage) {
            "question" -> PreviewPrimary("답변 시작", { onStage("listening") })
            "listening" -> { PreviewPrimary("답변 마치기", { if (sampleCase != "unclear") onAttempts(attempts + 1); onStage("processing") }); PreviewSecondary("취소", { onStage("question") }) }
            "processing" -> PreviewPrimary("확인 중", {}, enabled = false)
            "error" -> PreviewPrimary("피드백 다시 받기", { onCase("expression"); onStage("processing") })
            "unclear" -> PreviewPrimary("다시 답하기", { onStage("question") })
            "done" -> PreviewPrimary("뉴스 듣기", onBack)
            else -> { if (!enough && attempts < 2) PreviewPrimary("다시 답하기", { onStage("question") }) else PreviewPrimary("연습 마치기", onDone); PreviewSecondary("저장 후 나가기", onBack) }
        }
        if (stage != "done" && stage != "processing") TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("재생으로 돌아가기") }
    }) {
        BoxWithConstraints {
            val wide = maxWidth >= 1200.dp && LocalDensity.current.fontScale < 1.2f
            val support: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("답변 도움", style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { hint = !hint }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (hint) "힌트 닫기" else "힌트") }
                    if (hint) Text(story.summaryKo, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { meaning = !meaning }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (meaning) "질문 해석 닫기" else "질문 해석") }
                    if (meaning) Text(story.promptKo, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { example = !example }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (example) "답변 예시 닫기" else "답변 예시 보기") }
                    if (example) Text(story.answer, style = MaterialTheme.typography.bodyMedium)
                }
            }
            val response: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text(story.prompt, style = MaterialTheme.typography.headlineSmall)
                    Text("시연에서는 선택한 예시 답변으로 진행해요. 실제 마이크는 사용하지 않아요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box {
                        TextButton(onClick = { caseMenu = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("시연 답변: ${cases.first { it.first == sampleCase }.second}") }
                        DropdownMenu(expanded = caseMenu, onDismissRequest = { caseMenu = false }) {
                            cases.forEach { (id, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { onCase(id); onAttempts(0); caseMenu = false; onStage("question") }) }
                        }
                    }
                    if (!wide) {
                        TextButton(onClick = { helpExpanded = !helpExpanded }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (helpExpanded) "답변 도움 닫기" else "답변 도움") }
                        if (helpExpanded) support()
                    }
                    when (stage) {
                        "question" -> Text("영어로 한두 문장만 답해 주세요.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        "listening" -> { Text("예시 답변을 준비하고 있어요", style = MaterialTheme.typography.titleMedium); Text("답변 마치기를 누르면 예시 내용을 확인해요.", style = MaterialTheme.typography.bodyMedium) }
                        "processing" -> { Text("예시 답변을 확인하고 있어요", style = MaterialTheme.typography.titleMedium); Text(answer, style = MaterialTheme.typography.bodyLarge) }
                        "done" -> { Text("예시 연습을 마쳤어요", style = MaterialTheme.typography.titleMedium); Text("이 시연의 연습 기록에서 다시 볼 수 있어요.", style = MaterialTheme.typography.bodyMedium) }
                        "unclear" -> { Text("인식된 예시 답변이 없어요", style = MaterialTheme.typography.titleMedium); Text("답변을 확인하지 못했으므로 평가하지 않아요. 같은 질문으로 다시 진행해 보세요.", style = MaterialTheme.typography.bodyMedium) }
                        else -> {
                            Text("내 답변 · 예시", style = MaterialTheme.typography.titleSmall)
                            Text(answer, style = MaterialTheme.typography.bodyLarge)
                            Surface(color = if (stage == "error") MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) {
                                Text(when { stage == "error" -> "피드백을 불러오지 못했어요. 답변은 남아 있어요."; enough -> "기사의 핵심을 설명했어요. 이 답변으로 연습을 마칠 수 있어요."; sampleCase == "fact" -> story.summaryKo; else -> story.feedback },
                                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(20.dp))
                            }
                            if (sampleCase == "expression" && !enough && stage != "error") { Text("이렇게 말해보세요", style = MaterialTheme.typography.titleSmall); Text(story.after, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary) }
                        }
                    }
                }
            }
            if (wide) Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) { Box(Modifier.weight(2f)) { response() }; Box(Modifier.weight(1f)) { support() } }
            else Box(Modifier.widthIn(max = 1060.dp)) { response() }
        }
    }
}

@Composable
internal fun NativeHistory(stories: List<PreviewStory>, positions: Map<String, Int>, practiced: Map<String, Boolean>, onChoose: (Int) -> Unit, onNews: () -> Unit, onPractice: (Int) -> Unit, onBack: () -> Unit) {
    val heard = stories.filter { (positions[it.id] ?: 0) > 0 }
    val lessons = stories.filter { practiced[it.id] == true }
    PreviewPage("학습 기록", "이 실행에서 만든 시연 기록이에요. 실제 계정에는 저장하지 않아요.", onBack) {
        if (heard.isEmpty() && lessons.isEmpty()) {
            Text("아직 학습 기록이 없어요", style = MaterialTheme.typography.titleLarge)
            Text("뉴스를 듣고 예시 연습을 진행하면 여기에서 이어갈 수 있어요.", style = MaterialTheme.typography.bodyMedium)
            PreviewPrimary("뉴스 고르기", onNews)
        } else {
            Text("이어 듣기", style = MaterialTheme.typography.titleMedium)
            if (heard.isEmpty()) Text("아직 들은 뉴스가 없어요.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            heard.forEach { story -> TextButton(onClick = { onChoose(stories.indexOf(story)) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(story.title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text("${timeLabel(positions[story.id] ?: 0)}부터 · 원문 낭독", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("말하기 연습", style = MaterialTheme.typography.titleMedium)
            if (lessons.isEmpty()) Text("아직 예시 연습 기록이 없어요.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            lessons.forEach { story -> TextButton(onClick = { onPractice(stories.indexOf(story)) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(story.title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface); Text("예시 연습 완료", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } }
        }
    }
}

@Composable
internal fun NativeSettings(theme: String, onApply: (String) -> Unit, onClear: () -> Unit, onDiagnostics: () -> Unit, onBack: () -> Unit) {
    var draft by rememberSaveable(theme) { mutableStateOf(theme) }
    PreviewPage("듣기 설정", "현재 디버그 시연의 설정을 확인해요.", onBack, actions = { PreviewPrimary("설정 적용", { onApply(draft) }) }) {
        Text("화면 테마", style = MaterialTheme.typography.titleMedium)
        PreviewActionRow {
            listOf("system" to "자동", "light" to "밝게", "dark" to "어둡게").forEach { (id, label) ->
                Row(Modifier.heightIn(min = 56.dp).selectable(selected = draft == id, onClick = { draft = id }, role = Role.RadioButton).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = draft == id, onClick = null); Spacer(Modifier.width(8.dp)); Text(label)
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text("영어로 듣는 방식", style = MaterialTheme.typography.titleMedium)
        Text("원문 전체 낭독", style = MaterialTheme.typography.bodyLarge)
        Text("이 디버그 시연에는 영어 원문 오디오가 들어 있어요. 짧은 브리핑·후속 듣기 설정은 공유 목업에서 확인할 수 있어요.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(max = 1060.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text("시연 기록과 연결", style = MaterialTheme.typography.titleMedium)
        Text("로그인 없이 시연 중이에요. 실제 계정·사용자 DB·Gleo·마이크는 연결하지 않았어요.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PreviewActionRow { PreviewSecondary("시연 기록 지우기", onClear); PreviewSecondary("연결 점검", onDiagnostics) }
    }
}

@Composable
internal fun NativeQuestion(story: PreviewStory, onBack: () -> Unit) {
    PreviewPage("기사 내용 질문", story.title, onBack, actions = { PreviewPrimary("재생으로 돌아가기", onBack) }) {
        Text("이 기사의 핵심을 알려줘.", style = MaterialTheme.typography.titleLarge)
        Text(story.qa, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.widthIn(max = 1060.dp))
        Text("기사에 연결된 질문·답변 예시예요. 실제 Gleo 음성 호출은 사용하지 않아요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
