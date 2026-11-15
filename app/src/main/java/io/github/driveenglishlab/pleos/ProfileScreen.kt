package io.github.driveenglishlab.pleos

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.IdentityRecoveryState
import io.github.driveenglishlab.pleos.ui.*
import io.github.driveenglishlab.pleos.ui.components.*
import java.util.UUID
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Actual read-only records, never an example, new success judgement or presentation ACK. */
@Composable
fun ProfileScreen(state: ProductScreenState, send: (UiIntent) -> Unit) {
    if (state.source6History != null || state.source6SpeakingRecords.owner != null) {
        Source6ProfileScreen(state, send)
        return
    }
    val records = state.speakingRecords
    val detail = records.detail?.record
    if (detail != null) {
        SpeakingRecordDetail(state, detail, send)
        return
    }
    val history = state.history
    if (history == null) {
        ProductStatus(if (state.historyStatus == LoadStatus.FAILED) "기록을 불러오지 못했어요" else "학습 기록을 확인하고 있어요",
            "기존 기록과 이어할 학습은 유지돼요", loading = state.historyStatus == LoadStatus.LOADING) {
            IdentityRecoveryNotice(state)
            ProductButton("기록 다시 확인", { send(UiIntent.ReadHistory(state.origin)) })
        }
        return
    }
    val lessons = history.records.filterIsInstance<WirePersonalReadLesson>()
    val attempts = history.records.filterIsInstance<WirePersonalReadAttempt>()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text("내 학습 기록", style = MaterialTheme.typography.headlineMedium)
        IdentityRecoveryNotice(state)
        Text("이 차량에서 같은 학습 프로필을 함께 쓰면 같은 기록으로 남아요. 식별정보가 사라지거나 서버에 전송하지 못한 기록이 기기에서 사라지면 복구를 보장할 수 없어요", style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item { Text("내가 말할 수 있게 된 내용", style = MaterialTheme.typography.titleLarge) }
            if (records.loading && records.page == null) item { ProductNotice("말·표현 기록을 불러오고 있어요") }
            if (records.failed) item { ProductNotice("말·표현 기록을 불러오지 못했어요. 기존 기록은 유지돼요") }
            if (records.page != null && records.visibleItems.isEmpty()) item { ProductNotice("충분하게 영어로 설명한 내용이 쌓이면 이곳에서 볼 수 있어요") }
            items(records.visibleItems, key = { "speaking/${it.lessonId}" }) { record ->
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(record.articleTitle, style = MaterialTheme.typography.titleMedium)
                    val lessonId = UUID.fromString(record.lessonId)
                    val attemptId = UUID.fromString(record.representative.attemptId)
                    val current = matchingAssessment(state, lessonId, attemptId)
                    if (current?.displayReady == true && current.contentSuccess == true && current.reviewStatus != "corrected")
                        Text(record.meaningSummary, style = MaterialTheme.typography.bodyMedium)
                    AssessmentReviewPanel(state, lessonId, attemptId, "record", send, showFeedback = current?.reviewStatus == "corrected", showActions = false)
                    Text(practiceDate(record.representative.practiceTime), style = MaterialTheme.typography.bodySmall)
                    if (record.representative.formStatus == "unknown") Text("원래 답변의 문장 형태는 미확인이에요", style = MaterialTheme.typography.bodySmall)
                    ProductButton("내 말 살펴보기", { send(UiIntent.ShowSpeakingRecord(state.origin, UUID.fromString(record.lessonId))) }, secondary = true)
                }
            }
            if (records.page?.nextCursor != null) item { ProductButton("말·표현 기록 더 보기", { send(UiIntent.MoreSpeakingRecords(state.origin)) }, secondary = true) }
            item { Text("전체 연습과 이어하기", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 24.dp)) }
            if (lessons.isEmpty()) item {
                ProductStatus("아직 말하기 기록이 없어요", "기사를 들은 뒤 말하기 연습을 시작할 수 있어요")
            }
            items(lessons, key = { it.recordId }) { record ->
                val lesson = record.payload; val progress = lesson.state
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(state.catalog.firstOrNull { it.articleId.toString() == lesson.contentRef.articleId }?.title ?: "저장된 기사 연습", style = MaterialTheme.typography.titleLarge)
                    Text(when {
                        progress.pendingFeedbackAttemptId != null -> "아직 확인하지 않은 피드백이 있어요"
                        progress.pendingAttemptId != null -> "저장한 답변의 결과를 확인할 수 있어요"
                        progress.endReason == "retry_declined" -> "이번 연습을 마쳤어요 · 재답변 평가는 하지 않았어요"
                        progress.phase == "completed" -> "연습을 마쳤어요"
                        else -> "이어서 할 연습이 있어요"
                    }, style = MaterialTheme.typography.bodyMedium)
                    val latestAttempt = progress.pendingFeedbackAttemptId ?: progress.pendingAttemptId ?: progress.validRetryAttemptId ?: progress.validFirstAttemptId
                    latestAttempt?.let { attemptId ->
                        AssessmentReviewPanel(state, UUID.fromString(lesson.lessonId), UUID.fromString(attemptId), "record", send)
                    }
                    Text(when (lesson.priorExampleExposure.status) {
                        "exposed" -> "이 질문의 예문을 이전에 제공받았어요"
                        "unexposed_verified" -> "회차 시작 전 예문 미제공이 확인됐어요"
                        else -> "회차 시작 전 예문 제공 여부를 확인하지 못했어요"
                    }, style = MaterialTheme.typography.bodySmall)
                    if (lesson.supportRefs.isNotEmpty()) Text("도움 받은 회차 · ${lesson.supportRefs.size}개 자료", style = MaterialTheme.typography.bodySmall)
                    attempts.filter { it.payload.lessonId == lesson.lessonId }.forEach { attempt ->
                        Text(when(attempt.payload.englishAttemptStatus) {
                            WireCommonEnglishAttemptStatus.VERIFIED_ENGLISH -> "영어로 답한 기록이 있어요"
                            WireCommonEnglishAttemptStatus.NOT_ENGLISH -> "영어 답변으로 확인되지 않은 입력이 있어요"
                            else -> "원래 답변 언어 확인이 필요한 입력이 있어요"
                        }, style = MaterialTheme.typography.bodySmall)
                    }
                    if (progress.phase != "completed" || progress.pendingFeedbackAttemptId != null)
                        ProductButton("이 연습 이어하기", { send(UiIntent.ResumePractice(state.origin, UUID.fromString(lesson.lessonId))) })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            if (state.session.listening != null) ProductButton("이어서 듣기", { send(UiIntent.Resume(state.origin)) })
            ProductButton("기록 새로고침", { send(UiIntent.ReadHistory(state.origin)) }, secondary = true)
        }
    }
}


private fun practiceDate(value: WireProgressOriginalPracticeTime): String = when (value) {
    is WireProgressConfirmedPracticeTime -> "연습일 · " + DateTimeFormatter.ofPattern("yyyy년 M월 d일 HH:mm").format(Instant.parse(value.originalPracticedAt).atZone(ZoneId.of(value.zoneId)))
    is WireProgressUnknownPracticeTime -> "원래 연습일을 확인하지 못했어요"
}

@Composable
private fun SpeakingRecordDetail(state: ProductScreenState, record: WireProgressSpeakingRecordDetail, send: (UiIntent) -> Unit) {
    val id = UUID.fromString(record.lessonId)
    val representativeId = UUID.fromString(record.representative.attemptId)
    val representative = matchingAssessment(state, id, representativeId)
    val eligible = representative?.displayReady == true && representative.contentSuccess == true
    val latestId = record.latestResult.attemptId?.let(UUID::fromString)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item { Text(record.articleTitle, style = MaterialTheme.typography.titleLarge) }
            item { Text(record.questionText, style = MaterialTheme.typography.bodyMedium) }
            item { Text(practiceDate(record.representative.practiceTime), style = MaterialTheme.typography.bodySmall) }
            item { AssessmentReviewPanel(state, id, representativeId, "record", send, showFeedback = true) }
            if (eligible && representative?.reviewStatus != "corrected") item {
                Text("내가 설명한 내용", style = MaterialTheme.typography.titleMedium)
                Text(record.meaningSummary, style = MaterialTheme.typography.bodyMedium)
            }
            if (eligible && record.representative.formStatus == "confirmed") {
                record.representativeQuote?.takeIf { it.attemptId == record.representative.attemptId }?.let { quote -> item {
                    Text("그때 내가 한 말", style = MaterialTheme.typography.titleMedium)
                    Text(quote.text, style = MaterialTheme.typography.bodyLarge)
                } } ?: item { Text("확인된 원래 문장을 표시할 수 없어요", style = MaterialTheme.typography.bodySmall) }
            }
            if (latestId != null && latestId != representativeId) item {
                Text("최근 답변 판정", style = MaterialTheme.typography.titleMedium)
                AssessmentReviewPanel(state, id, latestId, "record", send, showFeedback = true)
            }
            item { Text("받았던 도움", style = MaterialTheme.typography.titleMedium)
                if (record.assistance.supportStatus == "unknown") Text("도움 기록 일부를 확인하지 못했어요", style = MaterialTheme.typography.bodySmall)
                record.assistance.representativeSupports.forEach { support ->
                    Text(when(support.role) {
                        "question_meaning" -> "질문 뜻 설명"
                        "core_explanation", "simple_explanation" -> "내용 설명"
                        "sentence_starter" -> "문장 시작 힌트"
                        "partial_correction" -> "일부 표현 교정"
                        else -> "완성 예문"
                    }, style = MaterialTheme.typography.bodySmall)
                }
                Text(when(record.assistance.atAttemptExampleExposure.status) {
                    "exposed" -> "이 답변 전 예문 제공이 확인됐어요"
                    "unexposed_verified" -> "이 답변 전 예문 미제공이 확인됐어요"
                    else -> "이 답변 전 예문 제공 여부는 미확인이에요"
                }, style = MaterialTheme.typography.bodySmall)
            }
            record.sourceAccess.reasonKo?.let { reason -> item { ProductNotice(reason) } }
            item { ProductButton("같은 질문으로 새 연습", { send(UiIntent.RepracticeRecord(state.origin, id)) }, enabled = record.sourceAccess.newPractice == "available") }
            item { ProductButton(if (record.visibility.hidden) "기록 숨김 해제" else "이 기록 숨기기", { send(UiIntent.HideSpeakingRecord(state.origin, id, !record.visibility.hidden)) }, secondary = true) }
        }
        ProductButton("기록 목록으로", { send(UiIntent.CloseSpeakingRecord(state.origin)) }, secondary = true)
    }
}

@Composable
internal fun IdentityRecoveryNotice(state: ProductScreenState, allowUnbound: Boolean = false) {
    state.identityRecovery?.takeIf { (it.state != IdentityRecoveryState.RESTORED || state.session.access.ownerValid) &&
        (it.owner == null || it.owner == state.session.owner ||
        (allowUnbound && state.session.owner == null && it.state != IdentityRecoveryState.RESTORED)) }?.let { recovery ->
        ProductNotice(when (recovery.state) {
            IdentityRecoveryState.RESTORED -> "서버에 저장된 기록이 연결됐어요. 다른 기기에서 아직 전송하지 않은 기록은 포함되지 않을 수 있어요"
            IdentityRecoveryState.SYNC_PENDING -> "학습 기록의 동기화를 확인하고 있어요"
            IdentityRecoveryState.RECOVERY_RETRYABLE -> "같은 기록의 연결을 다시 시도하고 있어요"
            IdentityRecoveryState.IDENTITY_UNAVAILABLE -> "이 기기의 식별 정보를 확인할 수 없어요. 이전 미로그인 기록의 복구는 보장되지 않아요"
            IdentityRecoveryState.ACCOUNT_SIGN_IN_REQUIRED -> "기존 연결을 확인하지 못했어요. 보호된 자료를 유지하며 연결 확인을 기다려요"
        })
    }
}
