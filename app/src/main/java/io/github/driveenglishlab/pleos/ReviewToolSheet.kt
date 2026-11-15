package io.github.driveenglishlab.pleos

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.driveenglishlab.pleos.data.wire.WireReviewSentenceEntry
import io.github.driveenglishlab.pleos.data.wire.WireReviewSentenceManifest
import io.github.driveenglishlab.pleos.domain.SessionScreen
import io.github.driveenglishlab.pleos.domain.PreparedContentRef
import io.github.driveenglishlab.pleos.domain.ContentRef
import io.github.driveenglishlab.pleos.domain.segmentSha256
import io.github.driveenglishlab.pleos.ui.ProductScreenState
import io.github.driveenglishlab.pleos.ui.UiIntent
import io.github.driveenglishlab.pleos.ui.components.ProductButton
import io.github.driveenglishlab.pleos.ui.components.ProductNotice
import io.github.driveenglishlab.pleos.ui.components.ProductStatus
import io.github.driveenglishlab.pleos.ui.theme.ProductMetrics
import java.util.UUID

/** An explicit auxiliary surface. Rendering never starts speech or fabricates a witness. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ReviewToolSheet(state: ProductScreenState, send: (UiIntent) -> Unit) {
    val tool = state.reviewTools
    if (!state.canRead || tool.owner != state.session.owner || tool.status == ReviewToolsStatus.CLOSED || state.session.practiceModal?.visible == true) return
    val preview = state.session.screen in setOf(SessionScreen.SETTINGS, SessionScreen.ONBOARDING) || tool.status == ReviewToolsStatus.PREVIEW ||
        tool.reason?.startsWith("PREVIEW_") == true || tool.reason == "REVIEWED_DEMO_UNAVAILABLE"
    val manifest = tool.manifest
    val script = manifest?.let { exactReviewScript(state, it) }
    val selected = tool.sentence?.takeIf { entry -> manifest?.entries?.singleOrNull { it.sentenceId == entry.sentenceId } == entry }
    Dialog(onDismissRequest = { send(UiIntent.CloseReviewTool(state.origin)) },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.widthIn(max = 1440.dp).fillMaxWidth(0.94f).fillMaxHeight(0.9f),
            color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (preview) "설정 미리듣기" else "문장 도움", style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).semantics { heading() })
                    ProductButton("닫기", { send(UiIntent.CloseReviewTool(state.origin)) }, secondary = true)
                }
                when {
                    tool.status == ReviewToolsStatus.LOADING -> ProductStatus("자료를 확인하고 있어요", "원래 기사와 듣던 구간은 유지돼요",
                        Modifier.weight(1f), loading = true)
                    preview -> ProductStatus(if (tool.status == ReviewToolsStatus.PREVIEW) "설정 미리듣기 · 일부 재생" else "미리듣기 자료를 준비하고 있어요",
                        if (tool.status == ReviewToolsStatus.PREVIEW) "설정에 맞는 짧은 예시예요. 답변 입력이나 학습 기록을 만들지 않아요. 닫은 뒤 원래 듣기는 직접 재생해 주세요"
                        else "설정은 그대로 적용할 수 있어요. 실제 뉴스와 학습 진행은 유지돼요", Modifier.weight(1f))
                    tool.commandPending -> ProductStatus("문장 도움 요청을 확인하고 있어요", "원래 듣기는 저장한 구간에서 기다려요. 닫아도 요청과 진행은 유지돼요",
                        Modifier.weight(1f), loading = true)
                    script == null || manifest == null -> ProductStatus("이 문장 자료를 지금 사용할 수 없어요",
                        "현재 대본과 같은 자료를 확인하지 못했어요. 다른 문장이나 번역으로 바꾸지 않아요", Modifier.weight(1f))
                    selected == null -> {
                        Text("도움이 필요한 문장을 골라 주세요", style = MaterialTheme.typography.bodySmall)
                        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            items(manifest.entries, key = { it.sentenceId }) { entry ->
                                exactSentence(script, entry)?.let { text ->
                                    val id = runCatching { UUID.fromString(entry.sentenceId) }.getOrNull()
                                    Surface(modifier = Modifier.fillMaxWidth().heightIn(min = ProductMetrics.touch)
                                        .clickable(enabled = id != null && !state.busy, role = Role.Button) { if (id != null) send(UiIntent.OpenSentence(state.origin, id)) },
                                        color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = MaterialTheme.shapes.medium) {
                                        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(24.dp))
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                            item { Text(exactSentence(script, selected) ?: "이 문장 자료를 지금 확인할 수 없어요", style = MaterialTheme.typography.bodyLarge) }
                            if (tool.status == ReviewToolsStatus.SPEECH_UNAVAILABLE) item {
                                ProductNotice("이 차량에서 문장 낭독을 사용할 수 없어요. 원래 기사와 구간은 유지돼요")
                            }
                            if (tool.status == ReviewToolsStatus.MEANING_UNAVAILABLE) item {
                                ProductNotice("이 문장의 한국어 뜻을 지금 제공할 수 없어요. 확인되지 않은 번역을 표시하지 않아요")
                            }
                            if (tool.status == ReviewToolsStatus.UNAVAILABLE) item {
                                ProductNotice("문장 도움을 지금 이용할 수 없어요. 원래 기사와 구간은 유지돼요")
                            }
                            if (tool.status == ReviewToolsStatus.MEANING_READY) item {
                                // A registered meaning may render before its delivery receipt.
                                // Rendering is never converted into a visibility ACK here.
                                val meaning = tool.meaning?.takeIf { it.manifestId.toString() == manifest.manifestId &&
                                    it.sentenceId.toString() == selected.sentenceId && it.hash == selected.meaningHash }
                                if (meaning != null) Text(meaning.text, style = MaterialTheme.typography.bodyLarge)
                                else ProductNotice("한국어 뜻의 제공 상태를 확인하고 있어요")
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ProductButton("문장 듣기", { send(UiIntent.SpeakSentence(state.origin)) },
                                enabled = !state.busy && selected.speechAvailability == "available" && exactSentence(script, selected) != null)
                            ProductButton("한국어 뜻", { send(UiIntent.ShowSentenceMeaning(state.origin)) },
                                enabled = !state.busy && selected.meaningAvailability == "available" && selected.meaningRef != null, secondary = true)
                        }
                        if (selected.speechAvailability != "available") ProductNotice("이 문장의 낭독 자료가 없어요")
                        if (selected.meaningAvailability != "available") ProductNotice("이 문장의 한국어 뜻이 아직 없어요")
                        Text("닫아도 원래 기사가 자동으로 재생되지는 않아요", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun exactReviewScript(state: ProductScreenState, manifest: WireReviewSentenceManifest): String? {
    val prepared = state.session.preparedListening
    val sourceMatches = if (prepared != null) when (val content = prepared.content) {
        is PreparedContentRef -> manifest.sourceKind == "prepared_components" && manifest.materialGenerationId == content.materialGenerationId.toString()
        is ContentRef -> manifest.sourceKind == "legacy_bundle" && manifest.bundleId == content.bundleId.toString()
    }
    else state.session.listening?.let { manifest.sourceKind == "legacy_bundle" && manifest.bundleId == it.content.bundleId.toString() } == true
    if (!sourceMatches) return null
    val current = state.preparedScript?.takeIf { it.scriptId.toString() == manifest.scriptId && it.textHash == manifest.textHash }?.text
        ?: state.reader?.script?.takeIf { it.scriptId.toString() == manifest.scriptId && it.textHash == manifest.textHash }?.text
    return current?.takeIf { segmentSha256(it) == manifest.textHash }
}

private fun exactSentence(script: String, entry: WireReviewSentenceEntry): String? {
    val count = script.codePointCount(0, script.length).toLong()
    if (entry.textStart < 0 || entry.textEnd <= entry.textStart || entry.textEnd > count) return null
    return script.substring(script.offsetByCodePoints(0, entry.textStart.toInt()), script.offsetByCodePoints(0, entry.textEnd.toInt()))
}
