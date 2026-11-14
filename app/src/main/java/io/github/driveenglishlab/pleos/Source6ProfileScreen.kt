package io.github.driveenglishlab.pleos

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.driveenglishlab.pleos.data.Source6SpeakingRecordsState
import io.github.driveenglishlab.pleos.data.Source6SpeakingRecordsStatus
import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.ui.*
import io.github.driveenglishlab.pleos.ui.components.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Read-only actual source6 history, including tagged legacy records. Reading and
 * displaying history never sends presentation ACKs or restarts an old lesson. */
@Composable
fun Source6ProfileScreen(state: ProductScreenState, send: (UiIntent) -> Unit) {
    if (!state.canRead) {
        ProductStatus("주차 후 기록을 확인해 주세요", "안전하게 주차하면 저장된 기록을 볼 수 있어요")
        return
    }
    val owner = state.session.owner
    val history = state.source6History?.takeIf { owner != null && it.profile.profileContext.profileId == owner.profileId.toString() && it.profile.profileContext.privacyEpoch == owner.privacyEpoch }
    val records = state.source6SpeakingRecords.takeIf { owner != null && it.owner == owner } ?: Source6SpeakingRecordsState()
    if (owner == null) {
        ProductStatus("기록을 연결하고 있어요", "사용자 확인이 끝나면 학습 기록을 볼 수 있어요", loading = true) { IdentityRecoveryNotice(state) }
        return
    }
    records.detail?.record?.let { detail -> Source6SpeakingDetail(state, records, detail, send); return }
    val lessons = history?.records?.filterIsInstance<WireSource6PersonalReadLesson>().orEmpty()
    val attempts = history?.records?.filterIsInstance<WireSource6PersonalReadAttempt>().orEmpty()
    val checkpoints = history?.records?.filterIsInstance<WireSource6PersonalReadCheckpoint>().orEmpty()
    val selections = history?.records?.filterIsInstance<WireSource6PersonalReadSelection>().orEmpty()
    val completed = history?.records?.filterIsInstance<WireSource6PersonalReadListeningCompletion>().orEmpty().map { when (val value = it.payload) {
        is WireSource6SegmentListeningCompletionRecord6 -> value.checkpointId
        is WireSource6LegacyListeningCompletionRecord6 -> value.legacyCompletion.checkpointId
    } }.toSet()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text("내 학습 기록", style = MaterialTheme.typography.headlineMedium)
        IdentityRecoveryNotice(state)
        Text("로그인 없이 함께 쓰면 같은 기록으로 남아요. 계정 전환은 주차 중에 할 수 있어요", style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item { Text("내가 말할 수 있게 된 내용", style = MaterialTheme.typography.titleLarge) }
            if (records.loading) item { ProductNotice("말·표현 기록을 불러오고 있어요") }
            if (records.failed) item { ProductNotice("말·표현 기록을 불러오지 못했어요. 기존 기록은 유지돼요") }
            if (records.status == Source6SpeakingRecordsStatus.EMPTY || records.status == Source6SpeakingRecordsStatus.READY && records.visibleItems.isEmpty()) item {
                ProductNotice("충분하게 영어로 설명한 내용이 쌓이면 이곳에서 볼 수 있어요")
            }
            items(records.visibleItems, key = { "source6-speaking/${it.lessonId}" }) { record ->
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(record.articleTitle, style = MaterialTheme.typography.titleMedium)
                    val lessonId = UUID.fromString(record.lessonId)
                    val attemptId = UUID.fromString(record.representative.attemptId)
                    val current = matchingAssessment(state, lessonId, attemptId)
                    if (current?.displayReady == true && current.contentSuccess == true && current.reviewStatus != "corrected")
                        Text(record.meaningSummary, style = MaterialTheme.typography.bodyMedium)
                    AssessmentReviewPanel(state, lessonId, attemptId, "record", send, showFeedback = current?.reviewStatus == "corrected", showActions = false)
                    Text(source6PracticeDate(record.representative.practiceTime), style = MaterialTheme.typography.bodySmall)
                    Text(formLabel(record.representative.formStatus), style = MaterialTheme.typography.bodySmall)
                    ProductButton("내 말 살펴보기", { send(UiIntent.ShowSpeakingRecord(state.origin, UUID.fromString(record.lessonId))) }, secondary = true, enabled = state.canRead && !state.busy)
                }
            }
            if (records.nextCursor != null) item { ProductButton("말·표현 기록 더 보기", { send(UiIntent.MoreSpeakingRecords(state.origin)) }, secondary = true, enabled = !records.loading && !state.busy) }
            item { Text("전체 연습과 이어하기", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 24.dp)) }
            if (history == null) item {
                ProductStatus(if (state.historyStatus == LoadStatus.FAILED) "전체 기록을 불러오지 못했어요" else "전체 기록을 확인하고 있어요", "기존 기록과 이어할 학습은 유지돼요", loading = state.historyStatus == LoadStatus.LOADING)
            } else if (lessons.isEmpty()) item { ProductNotice("아직 말하기 기록이 없어요. 기사를 들은 뒤 연습을 시작할 수 있어요") }
            items(lessons, key = { "source6-lesson/${it.recordId}" }) { row ->
                val lesson = row.payload; val progress = lesson.state
                val summary = records.items.firstOrNull { it.lessonId == lesson.lessonId }
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(summary?.articleTitle ?: sourceTitle(state, lesson.contentRef.contentRef()), style = MaterialTheme.typography.titleLarge)
                    Text(when {
                        progress.pendingFeedbackAttemptId != null -> "아직 확인하지 않은 피드백이 있어요"
                        progress.pendingAttemptId != null -> "저장한 답변의 결과를 확인할 수 있어요"
                        progress.endReason == "retry_declined" -> "이번 연습을 마쳤어요 · 재답변 평가는 하지 않았어요"
                        progress.phase == "completed" -> "연습을 마쳤어요"
                        else -> "이어서 할 연습이 있어요"
                    }, style = MaterialTheme.typography.bodyMedium)
                    Text(summary?.let { source6PracticeDate(it.representative.practiceTime) } ?: "원래 연습일을 확인하지 못했어요", style = MaterialTheme.typography.bodySmall)
                    val latestAttempt = progress.pendingFeedbackAttemptId ?: progress.pendingAttemptId ?: progress.validRetryAttemptId ?: progress.validFirstAttemptId
                    latestAttempt?.let { attemptId ->
                        AssessmentReviewPanel(state, UUID.fromString(lesson.lessonId), UUID.fromString(attemptId), "record", send)
                    }
                    Text(exposureLabel(lesson.priorExampleExposure.status, prior = true), style = MaterialTheme.typography.bodySmall)
                    attempts.filter { it.payload.lessonId == lesson.lessonId }.forEach { attempt ->
                        Text(englishLabel(attempt.payload.englishAttemptStatus.name), style = MaterialTheme.typography.bodySmall)
                    }
                    if (progress.phase != "completed" || progress.pendingFeedbackAttemptId != null)
                        ProductButton("이 연습 이어하기", { send(UiIntent.ResumePractice(state.origin, UUID.fromString(lesson.lessonId))) }, enabled = state.canRead && !state.busy)
                    if (summary?.sourceAccess?.newPractice == "available") ProductButton("같은 질문으로 새 연습", { send(UiIntent.RepracticeRecord(state.origin, UUID.fromString(lesson.lessonId))) }, secondary = true, enabled = state.canRead && !state.busy)
                    summary?.sourceAccess?.reasonKo?.let { ProductNotice(it) }
                }
            }
            item { Text("들었던 기사", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 24.dp)) }
            if (history != null && selections.isEmpty()) item { ProductNotice("아직 저장된 기사 청취 기록이 없어요") }
            items(selections, key = { "source6-selection/${it.recordId}" }) { row ->
                val selection = row.payload
                val checkpoint = checkpoints.filter { it.payload.selectionId == selection.selectionId }.maxByOrNull { it.version }?.payload
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(sourceTitle(state, selection.contentRef), style = MaterialTheme.typography.titleMedium)
                    Text(if (selection.contentRef.mode() == "original_full") "당시 원문으로 듣기" else "당시 브리핑으로 듣기", style = MaterialTheme.typography.bodySmall)
                    Text(if (checkpoint != null && checkpoint.checkpointId in completed) "끝까지 들은 기록이 있어요" else "저장된 청취 기록이에요", style = MaterialTheme.typography.bodySmall)
                    if (checkpoint != null) ProductButton("당시 기사 다시 듣기", { send(UiIntent.ReplayHistory(state.origin, UUID.fromString(selection.selectionId), UUID.fromString(checkpoint.checkpointId))) }, secondary = true, enabled = state.canRead && !state.busy)
                    else Text("이어들을 위치를 확인하지 못했어요", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.session.listening != null || state.session.preparedListening != null) ProductButton("이어서 듣기", { send(UiIntent.Resume(state.origin)) }, enabled = !state.busy)
            ProductButton("기록 새로고침", { send(UiIntent.ReadHistory(state.origin)) }, secondary = true, enabled = !state.busy)
        }
    }
}

@Composable
private fun Source6SpeakingDetail(state: ProductScreenState, records: Source6SpeakingRecordsState, record: WireSource6ProgressSpeakingRecordDetail, send: (UiIntent) -> Unit) {
    val id = UUID.fromString(record.lessonId)
    val representativeId = UUID.fromString(record.representative.attemptId)
    val representative = matchingAssessment(state, id, representativeId)
    val eligible = representative?.displayReady == true && representative.contentSuccess == true
    val latestId = record.latestResult.attemptId?.let(UUID::fromString)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item { Text(record.articleTitle, style = MaterialTheme.typography.titleLarge) }
            item { Text(record.questionText, style = MaterialTheme.typography.bodyMedium) }
            item { Text(source6PracticeDate(record.representative.practiceTime), style = MaterialTheme.typography.bodySmall) }
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
            item { Text(englishLabel(record.representative.englishAttemptStatus), style = MaterialTheme.typography.bodySmall); Text(formLabel(record.representative.formStatus), style = MaterialTheme.typography.bodySmall) }
            if (latestId != null && latestId != representativeId) item {
                Text("최근 답변 판정", style = MaterialTheme.typography.titleMedium)
                AssessmentReviewPanel(state, id, latestId, "record", send, showFeedback = true)
            }
            item {
                Text("받았던 도움", style = MaterialTheme.typography.titleMedium)
                if (record.assistance.supportStatus == "unknown") Text("도움 기록 일부를 확인하지 못했어요", style = MaterialTheme.typography.bodySmall)
                record.assistance.representativeSupports.forEach { support -> Text(supportLabel(support.role), style = MaterialTheme.typography.bodySmall) }
                Text(exposureLabel(record.assistance.atAttemptExampleExposure.status), style = MaterialTheme.typography.bodySmall)
            }
            record.sourceAccess.reasonKo?.let { reason -> item { ProductNotice(reason) } }
            if (records.failed) item { ProductNotice("기록을 새로 확인하지 못했어요. 표시 중인 기록은 유지돼요") }
            item { ProductButton("같은 질문으로 새 연습", { send(UiIntent.RepracticeRecord(state.origin, id)) }, enabled = record.sourceAccess.newPractice == "available" && state.canRead && !state.busy) }
            item { ProductButton(if (record.visibility.hidden) "기록 숨김 해제" else "이 기록 숨기기", { send(UiIntent.HideSpeakingRecord(state.origin, id, !record.visibility.hidden)) }, secondary = true, enabled = !state.busy && id.toString() !in records.conflicts) }
        }
        ProductButton("기록 목록으로", { send(UiIntent.CloseSpeakingRecord(state.origin)) }, secondary = true)
    }
}

private fun source6PracticeDate(value: WireSource6ProgressOriginalPracticeTime): String = when (value) {
    is WireSource6ProgressConfirmedPracticeTime -> runCatching { "연습일 · " + DateTimeFormatter.ofPattern("yyyy년 M월 d일 HH:mm").format(Instant.parse(value.originalPracticedAt).atZone(ZoneId.of(value.zoneId))) }.getOrDefault("원래 연습일을 확인하지 못했어요")
    is WireSource6ProgressUnknownPracticeTime -> "원래 연습일을 확인하지 못했어요"
}
private fun englishLabel(value: String) = when (value.lowercase()) {
    "verified_english" -> "영어로 답한 기록이에요"
    "not_english" -> "영어 답변으로 확인되지 않은 입력이에요"
    else -> "원래 답변 언어를 확인하지 못했어요"
}
private fun formLabel(value: String) = when (value) {
    "confirmed" -> "원래 답변의 문장 형태가 확인됐어요"
    else -> "원래 답변의 문장 형태는 미확인이에요"
}
private fun exposureLabel(value: String, prior: Boolean = false) = when (value) {
    "exposed" -> if (prior) "이 질문의 예문을 이전에 제공받았어요" else "이 답변 전 예문 제공이 확인됐어요"
    "unexposed_verified" -> if (prior) "회차 시작 전 예문 미제공이 확인됐어요" else "이 답변 전 예문 미제공이 확인됐어요"
    else -> if (prior) "회차 시작 전 예문 제공 여부를 확인하지 못했어요" else "이 답변 전 예문 제공 여부는 미확인이에요"
}
private fun supportLabel(value: String) = when (value) {
    "question_meaning" -> "질문 뜻 설명"
    "core_explanation", "simple_explanation" -> "내용 설명"
    "sentence_starter" -> "문장 시작 힌트"
    "partial_correction" -> "일부 표현 교정"
    "complete_example" -> "완성 예문"
    else -> "도움 자료"
}
private fun WireSource6PracticeContentRef6.contentRef(): WireSource6ContentRef6 = when (this) {
    is WireSource6LegacyPracticeRef6 -> legacyRef.let { WireSource6LegacyContentRef6("legacy_bundle", WireSource6LegacyContentRef5(it.articleId, it.bundleId, it.sourceSnapshotId, it.listeningMode, it.listeningScriptId)) }
    is WireSource6PreparedPracticeRef6 -> WireSource6PreparedContentRef6(sourceKind, articleId, sourceSnapshotId, sourceHash, materialGenerationId, mainComponentId, mainScriptId, mainTextHash, listeningMode)
}
private fun WireSource6ContentRef6.mode() = when (this) {
    is WireSource6LegacyContentRef6 -> legacyRef.listeningMode
    is WireSource6PreparedContentRef6 -> listeningMode
}
private fun sourceTitle(state: ProductScreenState, ref: WireSource6ContentRef6): String = when (ref) {
    is WireSource6PreparedContentRef6 -> state.preparedCatalog.firstOrNull { it.sourceSnapshotId == ref.sourceSnapshotId && it.sourceHash == ref.sourceHash }?.title
    is WireSource6LegacyContentRef6 -> state.catalog.firstOrNull { it.bundleId.toString() == ref.legacyRef.bundleId }?.title
} ?: "저장된 기사"
