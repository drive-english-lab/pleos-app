package io.github.driveenglishlab.pleos

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import io.github.driveenglishlab.pleos.domain.*
import io.github.driveenglishlab.pleos.platform.*
import io.github.driveenglishlab.pleos.ui.*
import io.github.driveenglishlab.pleos.ui.components.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch

/** Existing product typography/actions, bound to authoritative session/read projections. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun PracticeScreen(state: ProductScreenState, send: (UiIntent) -> Unit, requestMicrophone: () -> Unit) {
    val study = state.learning
    val practice = study.practice
    if (practice == null) {
        ProductStatus("연습을 준비하고 있어요", "듣던 기사와 진행을 유지하고 있어요", loading = true)
        return
    }
    val progress = practice?.progress
    val processing = SessionOperationKind.FEEDBACK in state.session.activeOperations
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Text(study.articleTitle ?: "같은 기사로 말하기", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(practice.questionEn, style = MaterialTheme.typography.bodyLarge)
            study.support?.let { binding ->
                Text(when(binding.artifact.role) {
                    SupportRole.QUESTION_MEANING -> "질문 뜻"
                    SupportRole.CORE_EXPLANATION, SupportRole.SIMPLE_EXPLANATION -> "내용 이해하기"
                    SupportRole.SENTENCE_STARTER -> "이렇게 시작해 보세요"
                    SupportRole.COMPLETE_EXAMPLE -> "예문"
                    SupportRole.PARTIAL_CORRECTION -> "표현 살펴보기"
                }, style = MaterialTheme.typography.titleMedium)
                ObservedSupportText(binding) { send(UiIntent.SupportWitness(state.origin, it)) }
                ProductButton("질문으로 돌아가기", { send(UiIntent.CloseSupport(state.origin)) }, secondary = true)
            } ?: when {
                state.session.inputPhase in setOf(InputPhase.CAPTURING, InputPhase.FINALIZING) -> {
                    Text(if (state.session.inputPhase == InputPhase.FINALIZING) "답변을 확인하고 있어요" else "영어로 답해 주세요", style = MaterialTheme.typography.titleMedium)
                    Text("끝나면 ‘답변 마치기’를 눌러 주세요", style = MaterialTheme.typography.bodySmall)
                }
                processing -> ProductStatus(if (practice.requestState == PracticeRequestState.PROCESSING_NOTICE) "피드백을 준비하고 있어요" else "답변을 저장하고 있어요",
                    "저장한 답변은 유지돼요", loading = true)
                practice.feedback != null -> {
                    val feedback = requireNotNull(practice.feedback)
                    Text(when(feedback.englishAttemptStatus) {
                        EnglishAttemptStatus.NOT_ENGLISH -> "영어로 다시 답해 보세요"
                        EnglishAttemptStatus.UNVERIFIED -> "원래 답변 언어 확인이 필요해요"
                        EnglishAttemptStatus.VERIFIED_ENGLISH -> if (feedback.judgement == Judgement.UNASSESSABLE) "답변을 다시 확인해 주세요" else "내 답변 피드백"
                    }, style = MaterialTheme.typography.titleMedium)
                    Text(feedback.feedbackKo, style = MaterialTheme.typography.bodyMedium)
                    if (feedback.progress.contentSuccess == true) Text("기사의 핵심 내용을 담아 말했어요", style = MaterialTheme.typography.bodySmall)
                    if (feedback.progress.retryOutcome != null) Text(when(feedback.progress.retryOutcome) {
                        "improved" -> "다시 말한 답변에 보완할 점이 반영됐어요"
                        else -> "다시 답한 결과를 저장했어요"
                    }, style = MaterialTheme.typography.bodySmall)
                    Unit
                }
                practice.progress == null -> ProductStatus("연습 연결을 확인해 주세요", "질문은 유지돼요. 연결을 확인한 뒤 다시 열 수 있어요")
                else -> {
                    Text("질문에 영어로 짧게 답해 보세요", style = MaterialTheme.typography.titleMedium)
                    Text("입력 경로가 확인된 차량에서 음성 인식을 사용해요. 답변 텍스트는 학습 기록에 저장하며 원음은 앱에 보관하지 않아요", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (study.microphonePermissionNeeded) {
                Text("직접 답변 입력에는 마이크 권한이 필요해요. 허용한 뒤 답변 시작을 다시 눌러 주세요", style = MaterialTheme.typography.bodySmall)
                ProductButton("마이크 권한 허용", requestMicrophone)
            }
        }
        if (study.support == null) FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                state.session.inputPhase == InputPhase.CAPTURING -> {
                    ProductButton("답변 마치기", { send(UiIntent.FinishAnswer(state.origin)) })
                    ProductButton("답변 취소", { send(UiIntent.CancelAnswer(state.origin)) }, secondary = true)
                }
                state.session.inputPhase == InputPhase.FINALIZING -> ProductButton("답변 취소", { send(UiIntent.CancelAnswer(state.origin)) }, secondary = true)
                !processing && practice.feedback != null && practice.progress?.pendingFeedbackAttemptId != null -> {
                    ProductButton(if (progress?.phase == "completed") "연습 마치기" else "다시 답하기", {
                        send(if (progress?.phase == "completed") UiIntent.FinishPractice(state.origin) else UiIntent.RetryAnswer(state.origin))
                    })
                    if (progress?.phase == "retry_offer") ProductButton("이번 연습 마치기", { send(UiIntent.FinishPractice(state.origin, true)) }, secondary = true)
                }
                !processing && practice.progress?.nextTurn != null -> ProductButton("답변 시작", { send(UiIntent.StartAnswer(state.origin)) })
            }
            if (!processing && study.lastAttemptId != null && practice.feedback == null) {
                ProductButton("저장된 결과 확인", { send(UiIntent.RetryFeedback(state.origin, true)) }, secondary = true)
                ProductButton("피드백 다시 요청", { send(UiIntent.RetryFeedback(state.origin)) }, secondary = true)
            }
            if (!processing && state.session.inputPhase == InputPhase.IDLE) {
                ProductButton("질문 뜻", { send(UiIntent.RequestHelp(state.origin, HelpRequest.QUESTION_MEANING)) }, secondary = true)
                ProductButton("힌트", { send(UiIntent.RequestHelp(state.origin, HelpRequest.HINT)) }, secondary = true)
                ProductButton("예문", { send(UiIntent.RequestHelp(state.origin, HelpRequest.EXAMPLE)) }, secondary = true)
            }
            ProductButton("저장 후 나가기", { send(UiIntent.FinishPractice(state.origin)) }, enabled = !processing, secondary = true)
        }
    }
}

/** Each line must actually be drawn inside the clipped current component viewport. Layout alone
 * or just composing an off-screen example never emits a witness. Scroll can cover separate lines.
 */
@Composable
private fun ObservedSupportText(binding: PresentationUiBinding, witnessed: (ArtifactWitness) -> Unit) {
    var layout by remember(binding.outputId) { mutableStateOf<TextLayoutResult?>(null) }
    var viewport by remember(binding.outputId) { mutableStateOf(Rect.Zero) }
    val seen = remember(binding.outputId, layout) { BooleanArray(layout?.lineCount ?: 0) }
    val emitted = remember(binding.outputId) { AtomicBoolean(false) }
    val latest by rememberUpdatedState(witnessed)
    val scope = rememberCoroutineScope()
    Text(binding.artifact.text, style = MaterialTheme.typography.bodyMedium, onTextLayout = { layout = it },
        modifier = Modifier.fillMaxWidth().onGloballyPositioned { coordinates ->
            val p = coordinates.positionInWindow(); val clip = coordinates.boundsInWindow()
            viewport = clip.translate(-p.x, -p.y)
        }.drawWithContent {
            drawContent()
            val result = layout
            if (result != null && viewport.width > 0 && viewport.height > 0) {
                for (line in 0 until result.lineCount) {
                    if (result.getLineTop(line) >= viewport.top && result.getLineBottom(line) <= viewport.bottom &&
                        result.getLineLeft(line) >= viewport.left && result.getLineRight(line) <= viewport.right) seen[line] = true
                }
                if (seen.isNotEmpty() && seen.all { it } && emitted.compareAndSet(false, true)) scope.launch {
                    latest(ArtifactWitness(binding.outputId, binding.artifact.textHash, ObservationMode.PARKED_VISIBLE, "native-support-all-lines-drawn-current-window"))
                }
            }
        })
}
