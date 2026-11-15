package io.github.driveenglishlab.pleos.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.driveenglishlab.pleos.domain.*
import io.github.driveenglishlab.pleos.ui.*
import io.github.driveenglishlab.pleos.ui.theme.ProductMetrics

/** Two article actions stay visible; optional changes remain in the current-article menu. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun ArticleActions(state: ProductScreenState, send: (UiIntent) -> Unit, onFormat: (ListeningMode) -> Unit,
    prepared: Boolean, modifier: Modifier = Modifier) {
    var expanded by remember(state.session.owner, state.session.preparedListening?.selectionId, state.session.listening?.selectionId) { mutableStateOf(false) }
    val mode = state.session.preparedListening?.content?.listeningMode ?: state.session.listening?.content?.listeningMode
    LaunchedEffect(state.canRead) { if (!state.canRead) expanded = false }
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ProductButton("기사에 질문하기", { send(UiIntent.OpenArticleQuestion(state.origin)) }, enabled = state.canRead && !state.busy, secondary = true)
        ProductButton("말하기 연습", { send(UiIntent.StartPractice(state.origin)) }, enabled = state.canRead && !state.busy, secondary = true)
        Box {
            ProductButton("기사 메뉴", { expanded = true }, enabled = state.canRead, secondary = true)
            DropdownMenu(expanded && state.canRead, onDismissRequest = { expanded = false }) {
                fun close(action: () -> Unit) { expanded = false; action() }
                DropdownMenuItem(text = { Text("기사 정보") }, onClick = { close { send(UiIntent.Navigate(state.origin, SessionScreen.ARTICLE_INFO)) } },
                    modifier = Modifier.heightIn(min = ProductMetrics.touch))
                if (prepared) DropdownMenuItem(text = { Text("문장 도움") }, onClick = { close { send(UiIntent.LoadSentences(state.origin)) } },
                    enabled = !state.busy, modifier = Modifier.heightIn(min = ProductMetrics.touch))
                DropdownMenuItem(text = { Text(if (state.formatPendingMode != null) "형식 변경 다시 확인" else if (mode == ListeningMode.BRIEFING) "원문으로 듣기" else "브리핑으로 듣기") },
                    onClick = { close { onFormat(state.formatPendingMode ?: if (mode == ListeningMode.BRIEFING) ListeningMode.ORIGINAL_FULL else ListeningMode.BRIEFING) } },
                    enabled = !state.busy, modifier = Modifier.heightIn(min = ProductMetrics.touch))
                HorizontalDivider()
                DropdownMenuItem(text = { Text(if (state.listenOnlyActive) "기본 진행으로 돌아가기" else "오늘은 듣기만") },
                    onClick = { close { send(UiIntent.ListeningOnly(state.origin, !state.listenOnlyActive)) } }, enabled = !state.busy,
                    modifier = Modifier.heightIn(min = ProductMetrics.touch))
            }
        }
    }
}
