package io.github.driveenglishlab.pleos

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.driveenglishlab.pleos.data.*
import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import io.github.driveenglishlab.pleos.ui.*
import io.github.driveenglishlab.pleos.ui.theme.DriveEnglishTheme
import kotlinx.serialization.json.*
import java.io.File
import java.time.Instant
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic renderer and intent regressions. Strict codec acceptance is a structural
 * fixture check, never evidence of a live review, news approval, Gleo or model quality. */
class R7ProductScreensTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val owner = OwnerContext(UUID.randomUUID(), 0, 1, UUID.randomUUID())
    private val allowed = AccessState(SafetyState(VehiclePosition.PARKED, true, true, true), WindowState(true, true),
        PowerAvailability.ALLOWED, true, true, true, true, true)
    private val content = PreparedContentRef(UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64), UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID(), "b".repeat(64), ListeningMode.ORIGINAL_FULL)
    private val task = PreparedPracticeRef(content, UUID.randomUUID(), UUID.randomUUID(), "synthetic-r7")
    private val lesson = UUID.randomUUID()
    private val attempt = UUID.randomUUID()
    private val oldFeedback = "SYNTHETIC OLD SUCCESS MUST NOT LEAK"
    private val oldQuote = "SYNTHETIC OLD QUOTE MUST NOT LEAK"
    private val correctedFeedback = "합성 정정: 연결 대상에 대한 설명을 더 확인해 주세요"
    private fun base() = ProductScreenState(session = SessionView(screen = SessionScreen.NEWS, owner = owner,
        ownerBinding = OwnerBindingStatus.VERIFIED, access = allowed))
    private fun render(frame: State<ProductScreenState>, intents: MutableList<UiIntent>, kind: String) {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                DriveEnglishTheme(DeviceAppearance.LIGHT) {
                    Surface(Modifier.requiredSize(1600.dp, 1100.dp).testTag("r7-frame")) {
                        when (kind) {
                            "catalog" -> CatalogViewsPanel(frame.value, { intents.add(it) }, { _, _ -> })
                            "practice" -> PracticeScreen(frame.value, { intents.add(it) }, {})
                            else -> ProfileScreen(frame.value, { intents.add(it) })
                        }
                    }
                }
            }
        }
    }
    private fun capture(name: String) {
        val bitmap = rule.onNodeWithTag("r7-frame").captureToImage().asAndroidBitmap()
        val directory = File(requireNotNull(rule.activity.getExternalFilesDir(null)), "ui-captures").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @After fun exportSyntheticCaptures() {
        val source = File(requireNotNull(rule.activity.getExternalFilesDir(null)), "ui-captures")
        val target = File(rule.activity.filesDir, "ui-captures").apply { mkdirs() }
        source.listFiles()?.filter { it.name.startsWith("r7-") && it.extension == "png" }?.forEach { it.copyTo(File(target, it.name), overwrite = true) }
    }
    @Test fun browsingViewAndSixFiltersOnlyEmitsReadIntentsAndExplicitSelectionKeepsArticleIdentity() {
        val labels = listOf("기술", "경제", "세계", "과학", "환경", "문화")
        val categories = CatalogCategoryId.entries.mapIndexed { i, id -> CatalogCategory(id, labels[i], true, i) }
        val key = CatalogArticleKey(content.articleId, content.sourceSnapshotId)
        val plain = CatalogArticleCard(key, "Synthetic R7 story", "Synthetic publisher", "https://example.invalid/r7", null,
            "news", setOf("technology"), emptyList(), null, null)
        val catalog = CatalogViewState(owner = owner, items = listOf(plain), categories = categories, availability = CatalogAvailability.READY)
        val frame = mutableStateOf(base().copy(catalogViews = catalog)); val intents = mutableListOf<UiIntent>()
        render(frame, intents, "catalog")
        assertTrue(intents.isEmpty())
        labels.forEach { rule.onNodeWithText(it).assertExists() }
        rule.onAllNodesWithText("최근 주요 이슈").filter(hasClickAction()).onFirst().performClick()
        rule.onNodeWithText("과학").performClick()
        assertEquals(2, intents.size)
        assertTrue(intents[0] is UiIntent.CatalogViewChanged)
        assertEquals(CatalogCategoryId.SCIENCE, (intents[1] as UiIntent.CatalogFilterChanged).category)
        val now = Instant.parse("2026-11-14T00:00:00Z")
        val evidence = CatalogIssueEvidence(true, listOf(CatalogHeadlineObservation(UUID.randomUUID(), "synthetic-route", 1, now, "c".repeat(64))),
            emptySet(), null, OriginIndependence.PENDING, null, now, "synthetic-issue-policy")
        val issue = CatalogIssueSummary(UUID.randomUUID(), 1, key.articleId, setOf(key.articleId), evidence, now, "synthetic-rank")
        rule.runOnIdle { frame.value = frame.value.copy(catalogViews = catalog.copy(view = CatalogView.ISSUES,
            categoryFilter = CatalogCategoryId.SCIENCE, items = listOf(plain.copy(issue = issue)))) }
        rule.onNodeWithText("목록 갱신 시각은 아직 확인되지 않았어요").assertExists()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("독립 원문 수는 아직 확인되지 않았어요"))
        rule.onNodeWithText("독립 원문 수는 아직 확인되지 않았어요").assertIsDisplayed()
        rule.onNodeWithText("확인된 독립 원문 · 0개").assertDoesNotExist()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("이 기사 듣기"))
        capture("r7-issue-unknown-evidence")
        rule.onNodeWithText("이 기사 듣기").performClick()
        val selected = intents.last() as UiIntent.SelectCatalogArticle
        assertEquals(key, selected.key); assertEquals(CatalogView.ISSUES, selected.originView)
        assertTrue(intents.all { it is UiIntent.CatalogViewChanged || it is UiIntent.CatalogFilterChanged || it is UiIntent.SelectCatalogArticle })
        assertEquals(catalog.items.single().key, selected.key)
    }
    private fun assessment(evaluation: String, review: String, text: String = correctedFeedback, judgement: String = "needs_more") =
        syntheticRendererAssessment(rule.activity, owner, lesson, attempt, task, evaluation, review, text, judgement)
    private fun practiceState(): ProductScreenState {
        val progress = PracticeProgress(2, attempt, null, null, attempt, "retry_offer", true, null, null)
        val practice = PracticeSession(owner, LessonInput(lesson, task, LessonInputPhase.QUESTION_WAIT, HelpSnapshot(),
            UUID.randomUUID(), DialogueProgress(false)), "What will the bridge connect?", progress,
            feedback = PracticeFeedback(attempt, Judgement.SUFFICIENT, "none", oldFeedback, null, EnglishAttemptStatus.VERIFIED_ENGLISH, progress))
        return base().copy(learning = LearningPresentation(owner = owner, practice = practice))
    }
    @Test fun missingPendingWithheldAndCorrectedAssessmentsNeverFallBackToOriginalFeedback() {
        val intents = mutableListOf<UiIntent>(); val frame = mutableStateOf(practiceState())
        render(frame, intents, "practice")
        rule.onNodeWithText("현재 판정을 확인해 주세요").assertIsDisplayed()
        rule.onNodeWithText(oldFeedback).assertDoesNotExist()
        rule.runOnIdle { frame.value = frame.value.copy(assessments = assessment("under_review", "in_review")) }
        rule.onNodeWithText("판정을 다시 확인하고 있어요").assertIsDisplayed()
        rule.onNodeWithText("다시 답하기").assertDoesNotExist()
        rule.onNodeWithText(oldFeedback).assertDoesNotExist()
        capture("r7-assessment-under-review")
        rule.onNodeWithText("처리 상태 다시 보기").performClick()
        assertTrue(intents.single() is UiIntent.ReadAssessment)
        assertFalse(intents.any { it is UiIntent.RetryAnswer || it is UiIntent.FinishAnswer || it is UiIntent.SupportWitness })
        rule.runOnIdle { frame.value = frame.value.copy(assessments = assessment("withheld", "held")) }
        rule.onNodeWithText("현재 판정을 보류했어요").assertIsDisplayed()
        rule.onNodeWithText(oldFeedback).assertDoesNotExist()
        rule.runOnIdle { frame.value = frame.value.copy(assessments = assessment("valid", "corrected")) }
        rule.onNodeWithText("판정을 정정했어요").assertIsDisplayed()
        rule.onNodeWithText(correctedFeedback).assertIsDisplayed()
        rule.onNodeWithText(oldFeedback).assertDoesNotExist()
        rule.onNodeWithText("현재 판정에서 기사의 핵심 내용을 담아 말한 것으로 확인됐어요").assertDoesNotExist()
        capture("r7-assessment-corrected")
        rule.onNodeWithText("판정 다시 확인").performClick()
        assertTrue(intents.last() is UiIntent.RequestAssessmentReview)
    }
    @Test fun recordUnderReviewHidesOldMeaningQuoteAndSuccessUntilCurrentEffectiveResultExists() {
        val legacyRef = WireCommonPracticeContentRef(content.articleId.toString(), UUID.randomUUID().toString(), content.sourceSnapshotId.toString(),
            "original_full", content.mainScriptId.toString(), UUID.randomUUID().toString(), task.basicQuestionVersionId.toString())
        val practiceTime = WireProgressUnknownPracticeTime("unknown", JsonNull, JsonNull, "unknown", JsonNull)
        val exposure = WireProgressExampleExposureSummary("unknown", null)
        val record = WireProgressSpeakingRecordDetail(null, null, lesson.toString(), legacyRef, "Synthetic saved story", "Synthetic saved question", oldFeedback,
            "generic_question_summary", WireProgressRepresentativeAnswer(attempt.toString(), "first", "verified_english", "sufficient", "confirmed", practiceTime),
            WireProgressLatestRecordResult(attempt.toString(), "sufficient", WireCommonEnglishAttemptStatus.VERIFIED_ENGLISH, "improved", "retry_evaluated"),
            WireProgressRecordAssistance("unknown", emptyList(), exposure, exposure), WireProgressRecordVisibility(false, 1),
            WireProgressRecordSourceAccess("unavailable", "available", null), WireProgressRepresentativeQuote(attempt.toString(), oldQuote, emptyList()), "unknown", emptyList())
        val records = SpeakingRecordsState(owner, detail = WireProgressSpeakingRecordView("5", "1", WireCommonProfileContext(owner.profileId.toString(), owner.privacyEpoch), 1, record))
        val recordTask = PracticeContentRef(ContentRef(UUID.fromString(legacyRef.articleId), UUID.fromString(legacyRef.bundleId),
            UUID.fromString(legacyRef.sourceSnapshotId), UUID.fromString(legacyRef.listeningScriptId), ListeningMode.ORIGINAL_FULL),
            UUID.fromString(legacyRef.taskId), UUID.fromString(legacyRef.basicQuestionVersionId))
        fun recordAssessment(evaluation: String, review: String, text: String = correctedFeedback, judgement: String = "needs_more") =
            syntheticRendererAssessment(rule.activity, owner, lesson, attempt, recordTask, evaluation, review, text, judgement)
        val frame = mutableStateOf(base().copy(speakingRecords = records, assessments = recordAssessment("under_review", "in_review")))
        val intents = mutableListOf<UiIntent>(); render(frame, intents, "record")
        rule.onNodeWithText("판정을 다시 확인하고 있어요").assertIsDisplayed()
        rule.onNodeWithText(oldFeedback).assertDoesNotExist(); rule.onNodeWithText(oldQuote).assertDoesNotExist()
        assertTrue(intents.isEmpty())
        rule.runOnIdle { frame.value = frame.value.copy(assessments = recordAssessment("valid", "corrected")) }
        rule.onNodeWithText(correctedFeedback).assertIsDisplayed()
        rule.onNodeWithText(oldQuote).assertDoesNotExist(); rule.onNodeWithText(oldFeedback).assertDoesNotExist()
        capture("r7-record-effective-overlay")
        rule.runOnIdle { frame.value = frame.value.copy(assessments = recordAssessment("valid", "kept", "합성 유지된 현재 판정", "sufficient")) }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(oldQuote))
        rule.onNodeWithText(oldQuote).assertIsDisplayed()
        assertTrue(intents.isEmpty())
    }
}

/** Full strict source6 feedback fixture, shared by the R6 modal renderer regression.
 * No provider output, real result revision or model-quality success is claimed. */
internal fun syntheticRendererAssessment(context: Context, owner: OwnerContext, lesson: UUID, attempt: UUID, task: PracticeRef,
    evaluation: String = "valid", review: String = "none", text: String, judgement: String = "sufficient"): AssessmentReviewState {
    val ref = when (task) {
        is PreparedPracticeRef -> task.content.let { source -> buildJsonObject {
            put("source_kind", "prepared_components"); put("article_id", source.articleId.toString()); put("source_snapshot_id", source.sourceSnapshotId.toString())
            put("source_hash", source.sourceHash); put("material_generation_id", source.materialGenerationId.toString()); put("main_component_id", source.mainComponentId.toString())
            put("main_script_id", source.mainScriptId.toString()); put("main_text_hash", source.mainTextHash); put("listening_mode", "original_full")
            put("practice_component_id", task.practiceComponentId.toString()); put("basic_question_version_id", task.basicQuestionVersionId.toString()); put("task_revision", task.taskRevision)
        } }
        is PracticeContentRef -> buildJsonObject {
            put("source_kind", "legacy_bundle"); put("legacy_ref", buildJsonObject {
                put("article_id", task.content.articleId.toString()); put("bundle_id", task.content.bundleId.toString()); put("source_snapshot_id", task.content.sourceSnapshotId.toString())
                put("listening_mode", "original_full"); put("listening_script_id", task.content.listeningScriptId.toString()); put("task_id", task.taskId.toString()); put("basic_question_version_id", task.basicQuestionVersionId.toString())
            })
        }
    }
    val feedback = buildJsonObject {
        put("attempt_id", attempt.toString()); put("content_ref", ref); put("judgement", judgement); put("focus", if (judgement == "sufficient") "none" else "missing_fact")
        put("feedback_ko", text); put("evidence_refs", JsonArray(emptyList())); put("correction", JsonNull); put("example_sentence", JsonNull)
        put("support_issued", JsonArray(emptyList())); put("focus_fact_refs", JsonArray(emptyList())); put("retry_outcome", JsonNull); put("prepared_supports", JsonArray(emptyList()))
        put("english_attempt_status", "verified_english")
        put("retry", buildJsonObject { put("suggested", false); put("prompt_text", JsonNull); put("prompt_language", "ko"); put("mode", "none") })
        put("generation", buildJsonObject { put("mode", "fixture"); put("model_revision", "synthetic-renderer"); put("prompt_version", "synthetic-renderer"); put("validator_version", "synthetic-renderer"); put("rubric_version", "synthetic-renderer") })
    }
    fun canonical(value: JsonElement): String = when (value) {
        is JsonObject -> value.entries.sortedBy { it.key }.joinToString(",", "{", "}") { JsonPrimitive(it.key).toString() + ":" + canonical(it.value) }
        is JsonArray -> value.joinToString(",", "[", "]") { canonical(it) }
        else -> value.toString()
    }
    val hash = segmentSha256(canonical(feedback))
    val effective = buildJsonObject {
        put("lesson_id", lesson.toString()); put("attempt_id", attempt.toString()); put("assessment_revision", 1)
        put("basis_assessment_hash", hash); put("original_feedback_hash", "d".repeat(64)); put("review_status", review); put("evaluation_status", evaluation)
        put("reason_code", JsonNull); put("effective_feedback_hash", if (evaluation == "valid") JsonPrimitive(hash) else JsonNull)
        put("feedback_format", if (evaluation == "valid") JsonPrimitive("source6") else JsonNull); put("feedback", if (evaluation == "valid") feedback else JsonNull)
        put("correction_event_id", if (review == "corrected") JsonPrimitive(UUID.randomUUID().toString()) else JsonNull); put("projection_revision", 1)
    }
    IncrementalContractJson(context).validateDefinition("assessment-review", "EffectiveAssessment", effective)
    return AssessmentReviewState(owner, mapOf(attempt to AssessmentEntryState(owner, lesson, attempt, effective,
        pending = evaluation == "under_review")))
}
