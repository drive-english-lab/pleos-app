package io.github.driveenglishlab.pleos

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.driveenglishlab.pleos.data.AssessmentEntryState
import io.github.driveenglishlab.pleos.ui.*
import io.github.driveenglishlab.pleos.ui.components.ProductButton
import java.util.UUID

/** Only a matching, strict server projection can authorize displaying an assessment.
 * An absent entry, POST receipt or old feedback is never an effective-result fallback.
 */
internal fun matchingAssessment(state: ProductScreenState, lessonId: UUID, attemptId: UUID): AssessmentEntryState? {
    val owner = state.session.owner ?: return null
    if (state.assessments.owner != owner) return null
    return state.assessments.entries[attemptId]?.takeIf { it.owner == owner && it.lessonId == lessonId && it.attemptId == attemptId }
}
internal fun assessmentDisplayReady(state: ProductScreenState, lessonId: UUID, attemptId: UUID): Boolean =
    matchingAssessment(state,lessonId,attemptId)?.displayReady == true
internal fun assessmentContentSuccess(state: ProductScreenState, lessonId: UUID, attemptId: UUID): Boolean =
    matchingAssessment(state,lessonId,attemptId)?.let { it.displayReady && it.contentSuccess == true } == true

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun AssessmentReviewPanel(state: ProductScreenState, lessonId: UUID, attemptId: UUID, entry: String,
    send: (UiIntent) -> Unit, showFeedback: Boolean = false, showActions: Boolean = true) {
    val result = matchingAssessment(state,lessonId,attemptId)
    val title = when {
        result?.loading == true -> "현재 판정을 확인하고 있어요"
        result?.pending == true || result?.evaluationStatus == "under_review" || result?.reviewStatus == "in_review" -> "판정을 다시 확인하고 있어요"
        result?.evaluationStatus == "withheld" -> "현재 판정을 보류했어요"
        result?.failed == true -> "현재 판정을 확인하지 못했어요"
        result?.displayReady == true && result.reviewStatus == "corrected" -> "판정을 정정했어요"
        result?.displayReady == true && result.reviewStatus == "kept" -> "판정을 유지했어요"
        result?.displayReady == true -> "현재 판정"
        else -> "현재 판정을 확인해 주세요"
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        result?.reasonKo?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (result?.displayReady == true) {
            if (showFeedback) result.feedbackKo?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        } else Text(when {
            result?.pending == true || result?.evaluationStatus == "under_review" -> "원래 답변은 보존돼요. 확인이 끝나기 전에는 이전 결과를 현재 판정으로 표시하지 않아요"
            result?.evaluationStatus == "withheld" -> "원래 답변과 연습 기록은 남아 있어요. 지금은 성공이나 교정 결과를 확정하지 않아요"
            else -> "원래 답변과 연습 기록은 유지돼요. 현재 유효한 결과를 먼저 확인해 주세요"
        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (showActions) FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ProductButton(if (result?.pending == true || result?.reviewStatus == "in_review") "처리 상태 다시 보기" else "현재 판정 확인", {
                send(UiIntent.ReadAssessment(state.origin,lessonId,attemptId,entry))
            }, enabled = result?.loading != true && !state.busy, secondary = true)
            if (result?.canRetry == true) ProductButton("재검토 다시 요청", {
                send(UiIntent.RetryAssessmentReview(state.origin,lessonId,attemptId,entry))
            }, enabled = !state.busy && !result.pending, secondary = true)
            else if (result?.canRequest == true) ProductButton("판정 다시 확인", {
                send(UiIntent.RequestAssessmentReview(state.origin,lessonId,attemptId,entry))
            }, enabled = !state.busy && !result.pending, secondary = true)
        }
    }
}
