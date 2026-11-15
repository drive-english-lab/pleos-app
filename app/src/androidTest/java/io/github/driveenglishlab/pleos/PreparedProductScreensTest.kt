package io.github.driveenglishlab.pleos

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
import androidx.test.platform.app.InstrumentationRegistry
import io.github.driveenglishlab.pleos.data.IncrementalContractJson
import io.github.driveenglishlab.pleos.data.SourcePublicScript
import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import io.github.driveenglishlab.pleos.platform.SegmentRuntimeState
import io.github.driveenglishlab.pleos.platform.SegmentRuntimeStatus
import io.github.driveenglishlab.pleos.platform.PresentationUiBinding
import io.github.driveenglishlab.pleos.ui.*
import io.github.driveenglishlab.pleos.ui.theme.DriveEnglishTheme
import java.io.File
import java.net.URI
import java.time.Instant
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic renderer/typed-intent regressions only. No Auth, actual news approval,
 * model, Gleo, microphone, speech completion or vehicle-power capability is exercised.
 * Source6 values and script partitions are real typed/validated fixture structures;
 * their visible learning outcomes remain synthetic, never live quality evidence.
 */
class PreparedProductScreensTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val owner = OwnerContext(UUID.randomUUID(), 0, 1, UUID.randomUUID())
    private val fragments = listOf("A city plans a bridge. ", "It will connect two districts. ", "Work is planned for next year.")
    private val script = fragments.joinToString("")
    private val scriptId = UUID.randomUUID()
    private val scriptHash = segmentSha256(script)
    private val manifest = run {
        var frontier = 0
        val segments = fragments.mapIndexed { index, text ->
            val start = frontier; frontier += text.codePointCount(0, text.length)
            SpeechSegment(UUID.randomUUID(), index, UUID.randomUUID(), start, frontier, segmentSha256(text))
        }
        val initial = SegmentManifest(UUID.randomUUID(), scriptId, scriptHash, 1, "0".repeat(64), SegmentLanguage.EN, segments)
        initial.copy(manifestHash = segmentSha256(initial.canonicalPayload())).also { it.validate(script) }
    }
    private val content = PreparedContentRef(UUID.randomUUID(), UUID.randomUUID(), scriptHash, UUID.randomUUID(),
        UUID.randomUUID(), scriptId, scriptHash, ListeningMode.ORIGINAL_FULL)
    private val cursor = PlaybackCursor6.Segment(scriptId, scriptHash, manifest.manifestId, 1, manifest.manifestHash,
        SegmentPhase.MAIN, manifest.segments[1].segmentId, 1, false, PauseReason.USER)
    private val work = PreparedListeningWorkRef(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), content, cursor, 1)
    private val wireRef = WireSource6PreparedContentRef6("prepared_components", content.articleId.toString(), content.sourceSnapshotId.toString(),
        content.sourceHash, content.materialGenerationId.toString(), content.mainComponentId.toString(), scriptId.toString(), scriptHash, "original_full")
    private val wireManifest = WireSource6SegmentManifest(manifest.manifestId.toString(), scriptId.toString(), scriptHash, 1, manifest.manifestHash, "en",
        manifest.segments.map { WireSource6Segment(it.segmentId.toString(), it.order.toLong(), it.sentenceId.toString(),
            it.startCodePoint.toLong(), it.endCodePoint.toLong(), it.textHash) })
    private val published = SourcePublicScript(wireRef, content.mainComponentId, scriptId, script, scriptHash, "en", wireManifest)
    private val discovery = WirePreparationDiscovery(content.articleId.toString(), content.sourceSnapshotId.toString(), content.sourceHash,
        "Synthetic renderer bridge story", listOf("technology"),
        WirePreparationDiscoverySource("Synthetic renderer fixture", "https://example.invalid/synthetic-bridge", "2026-11-14 00:00 UTC",
            "2026-11-14T00:00:00Z", "verified", "news"), "synthetic-renderer-no-real-rights", emptyList(), 3, null, null, null)
    // Synthetic component/route evidence is explicit; raw AWAKE never supplies approval.
    private val component = ComponentAccess(known = true, allowed = true, sourceGeneration = 0)
    private val components = ComponentPolicies(generation = 0, audio = component, media = component, microphone = component)
    private val allowed = AccessState(SafetyState(VehiclePosition.PARKED, true, true, true), WindowState(true, true),
        PowerAvailability.ALLOWED, true, true, true, true, true,
        components = components, mediaRouteAvailable = true, mediaFocusAllowed = true,
        backgroundMediaAllowed = true, gleoRouteAvailable = true, gleoVehicleAllowed = true,
        parkedOutputAvailable = true)
    private val categoryNames = linkedMapOf("technology" to "기술", "science" to "과학", "business" to "경제", "world" to "세계", "environment" to "환경", "culture" to "문화")
    private val categories = WirePreparationCategoryPublicProjection(1, "2026-11-14T00:00:00Z", categoryNames.entries.mapIndexed { index, item ->
        WirePreparationCategory(item.key, item.value, "${item.value} 분야의 합성 설정 검증", true, index.toLong(), "synthetic-supply", "synthetic-freshness")
    })
    private val settings = WireCommonSettings(listOf("technology"), "original_full", "none", emptyList(), true, 1)
    private val lessonId = UUID.randomUUID()
    private val task = PreparedPracticeRef(content, UUID.randomUUID(), UUID.randomUUID(), "synthetic-task-r1")
    private val question = "What will the bridge connect?"
    private val questionProgress = PracticeProgress(1, null, null, null, null, "question_wait", null, null, null)
    private val practice = PracticeSession(owner, LessonInput(lessonId, task, LessonInputPhase.QUESTION_WAIT,
        HelpSnapshot(), work.checkpointId, DialogueProgress(false)), question, questionProgress)
    private val original = ReviewReturnSnapshot(ProfileContext(owner.profileId, owner.privacyEpoch), work.selectionId, work.checkpointId,
        1, lessonId, SegmentPhase.MAIN, cursor, false, PauseReason.USER, 0)
    private val token = PracticeModalToken(owner, work.selectionId, lessonId, UUID.randomUUID(), 1)

    private fun player(): ProductScreenState {
        val program = SegmentProgram(owner, work.selectionId, content.segmentSourceKey(), manifest, script, SegmentPhase.MAIN, SegmentPurpose.MAIN)
        val run = UUID.randomUUID()
        val snapshot = SegmentPlaybackSnapshot(program, cursor, run, 1, SequentialCompletion.start(program, run, 1), bindingAcknowledged = true)
        return ProductScreenState(session = SessionView(screen = SessionScreen.PLAYER, owner = owner, ownerBinding = OwnerBindingStatus.VERIFIED,
            access = allowed, preparedListening = work, mediaConnected = true, intent = PlaybackIntent.USER_PAUSED),
            catalogStatus = LoadStatus.READY, preparedCatalog = listOf(discovery), categoryConfig = categories, preparedSource = discovery,
            preparedScript = published, readerStatus = LoadStatus.READY, segmentPlayback = SegmentRuntimeState(snapshot, SegmentRuntimeStatus.READY))
    }
    private fun modal(base: ProductScreenState = player(), feedback: Boolean = false): ProductScreenState {
        val attempt = UUID.randomUUID()
        val progress = if (feedback) PracticeProgress(2, attempt, null, null, attempt, "retry_offer", true, null, null) else questionProgress
        val projected = if (feedback) practice.copy(progress = progress, requestState = PracticeRequestState.STORED,
            feedback = PracticeFeedback(attempt, Judgement.SUFFICIENT, "none", "합성 피드백: 두 지역을 연결한다는 내용을 담았어요", null,
                EnglishAttemptStatus.VERIFIED_ENGLISH, progress)) else practice
        val phase = if (feedback) PracticeModalPhase.FEEDBACK else PracticeModalPhase.QUESTION
        return base.copy(session = base.session.copy(screen = SessionScreen.PLAYER, task = task, lessonId = lessonId,
            practiceModal = PracticeModalSession(token, phase, PracticeModalVisibility.VISIBLE, original, ReviewEntryOrigin.NEWS,
                PracticeModalLesson(phase, progress.version, if (feedback) attempt else null))),
            learning = LearningPresentation(owner = owner, articleTitle = discovery.title, practice = projected),
            assessments = if (feedback) syntheticRendererAssessment(rule.activity, owner, lessonId, attempt, task,
                text = "합성 피드백: 두 지역을 연결한다는 내용을 담았어요") else base.assessments)
    }
    private fun validateTypedSources() {
        val codec = IncrementalContractJson(rule.activity)
        codec.encode("source6", "PreparedContentRef6", wireRef, WireSource6PreparedContentRef6.serializer())
        codec.encode("source6", "SegmentManifest", wireManifest, WireSource6SegmentManifest.serializer())
        codec.encode("preparation", "Discovery", discovery, WirePreparationDiscovery.serializer())
        codec.encode("preparation", "CategoryPublicProjection", categories, WirePreparationCategoryPublicProjection.serializer())
    }
    private fun render(frame: State<ProductScreenState>, intents: MutableList<UiIntent>, width: Int = 1600, scale: Float = 1f) {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                DriveEnglishTheme(frame.value.appearance) {
                    Surface(Modifier.requiredSize(width.dp, 1100.dp).testTag("prepared-test-frame")) {
                        NewsScreens(frame.value, { intents.add(it) }, {}, { _, _ -> })
                    }
                }
            }
        }
    }
    private fun capture(name: String, dialog: Boolean = false) {
        rule.waitForIdle()
        val bitmap = if (dialog) requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            else rule.onNodeWithTag("prepared-test-frame").captureToImage().asAndroidBitmap()
        val directory = File(requireNotNull(rule.activity.getExternalFilesDir(null)), "ui-captures").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @After fun exportSyntheticCaptures() {
        val source = File(requireNotNull(rule.activity.getExternalFilesDir(null)), "ui-captures")
        val target = File(rule.activity.filesDir, "ui-captures").apply { mkdirs() }
        source.listFiles()?.filter { it.isFile && it.name.startsWith("prepared-") && it.extension == "png" }
            ?.forEach { it.copyTo(File(target, it.name), overwrite = true) }
    }

    /** Same fixture/capture code is built on the fixed Before and optional After sources. */
    @Test fun optionalUiComparisonFrames() {
        validateTypedSources()
        val narrow = InstrumentationRegistry.getArguments().getString("comparison") == "narrow"
        val theme = if (narrow) DeviceAppearance.DARK else DeviceAppearance.LIGHT
        val suffix = if (narrow) "narrow-dark" else "wide-light"
        val frame = mutableStateOf(player().copy(appearance = theme))
        val intents = mutableListOf<UiIntent>()
        render(frame, intents, width = if (narrow) 900 else 1600, scale = if (narrow) 1.3f else 1f)
        val directory = File(rule.activity.filesDir, "ui-option").apply { mkdirs() }
        fun shot(name: String, dialog: Boolean = false) {
            rule.waitForIdle()
            val node = if (dialog) rule.onNode(isDialog()) else rule.onNodeWithTag("prepared-test-frame")
            node.captureToImage().asAndroidBitmap().let { bitmap ->
                File(directory, "$name-$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        val reader = rule.onNodeWithTag("focused-transcript").fetchSemanticsNode().boundsInRoot
        val play = rule.onNodeWithContentDescription("재생").fetchSemanticsNode().boundsInRoot
        val current = rule.onNodeWithText(fragments[1]).fetchSemanticsNode().boundsInRoot
        val metrics = org.json.JSONObject().put("fixture", "synthetic-renderer-bridge-3-segments")
            .put("width", if (narrow) 900 else 1600).put("height", 1100).put("fontScale", if (narrow) 1.3 else 1.0)
            .put("theme", theme.name).put("readerHeight", reader.height.toDouble())
            .put("readerCenterX", reader.center.x.toDouble()).put("playCenterX", play.center.x.toDouble())
            .put("currentCenterY", current.center.y.toDouble()).put("readerCenterY", reader.center.y.toDouble())
        File(directory, "metrics-$suffix.json").writeText(metrics.toString(2))
        shot("player")
        rule.runOnIdle { frame.value = modal(frame.value) }
        rule.onNodeWithText(question).assertIsDisplayed()
        shot("practice", dialog = true)
        rule.runOnIdle {
            val base = player().copy(appearance = theme)
            frame.value = base.copy(session = base.session.copy(screen = SessionScreen.SETTINGS), draft = SettingsDraft(settings))
        }
        rule.onNodeWithText("듣기 설정").assertIsDisplayed()
        shot("settings")
        assertTrue("Renderer capture must not emit a product command", intents.isEmpty())
    }

    /** After-layout impact fixture only: all content, approval and input states are synthetic. */
    @Test fun compactAfterModalKeepsLongContentAndTypedActionsReachable() {
        val longQuestion = ("What will the bridge connect, and how might the planned connection change travel between the two districts? ").repeat(4).trim()
        val base = modal().let { it.copy(appearance = DeviceAppearance.DARK,
            learning = it.learning.copy(practice = it.learning.practice!!.copy(questionEn = longQuestion))) }
        val frame = mutableStateOf(base); val intents = mutableListOf<UiIntent>()
        render(frame, intents, width = 900, scale = 1.3f)
        rule.onNode(isDialog()).assertIsDisplayed()
        rule.onNodeWithText(longQuestion).assertExists()
        rule.onNodeWithText("기사로 돌아가기").assertIsDisplayed()
        rule.onNodeWithText("힌트").assertIsDisplayed().performClick()
        val request = intents.single() as UiIntent.RequestHelp
        assertEquals(HelpRequest.HINT, request.request); assertEquals(base.origin, request.origin)

        val helpText = ("합성 도움: 두 지역을 연결한다는 사실과 내년 착공 계획을 나누어 생각해 보세요. ").repeat(10).trim()
        val artifact = SupportArtifact("synthetic-after-long-help", HelpKind.FACT_EXPLANATION, SupportRole.CORE_EXPLANATION,
            SupportLanguage.KO, helpText, segmentSha256(helpText), SupportOrigin.PUBLISHED_TASK, null)
        val presentation = PracticePresentationRecord(UUID.randomUUID(), task, lessonId, InteractionChannel.PARKED,
            listOf(artifact), 1, Instant.parse("2026-11-14T00:00:00Z"), PresentationStatus.PREPARED, null)
        val ticket = PracticePresentationTicket(UUID.randomUUID(), ProfileContext(owner.profileId, owner.privacyEpoch), owner,
            presentation, QuestionExampleExposure(task.questionRef, ExposureStatus.UNKNOWN, ExposureBasis.HISTORY_GAP, 0, 1, null))
        rule.runOnIdle {
            val current = requireNotNull(base.session.practiceModal)
            frame.value = base.copy(session = base.session.copy(practiceModal = current.copy(phase = PracticeModalPhase.HELP,
                parentState = PracticeModalPhase.QUESTION, helpRequestId = UUID.randomUUID())),
                learning = base.learning.copy(support = PresentationUiBinding(owner, task, lessonId, ticket, UUID.randomUUID(), artifact)))
        }
        rule.onNodeWithText(helpText).assertExists()
        rule.onNodeWithText("질문으로 돌아가기").performScrollTo().assertIsDisplayed().performClick()
        assertTrue(intents.filterNot { it is UiIntent.SupportWitness }.last() is UiIntent.CloseSupport)
        capture("prepared-after-long-help-narrow-large", dialog = true)

        val feedbackText = ("합성 피드백: 두 지역의 연결과 착공 계획을 담았어요. 다음 답변에서는 연결이 이동에 주는 변화를 한 문장으로 덧붙여 보세요. ").repeat(5).trim()
        rule.runOnIdle {
            val feedback = modal(base, feedback = true)
            val attempt = requireNotNull(feedback.learning.practice?.feedback?.attemptId)
            frame.value = feedback.copy(learning = feedback.learning.copy(practice = feedback.learning.practice!!.copy(questionEn = longQuestion)),
                assessments = syntheticRendererAssessment(rule.activity, owner, lessonId, attempt, task, text = feedbackText))
        }
        rule.onNodeWithText(feedbackText).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("다시 답하기").assertIsDisplayed()
        rule.onNodeWithText("기사로 돌아가기").assertIsDisplayed().performClick()
        assertTrue(intents.filterNot { it is UiIntent.SupportWitness }.last() is UiIntent.FinishPractice)
        capture("prepared-after-long-feedback-narrow-large", dialog = true)

        rule.runOnIdle { frame.value = base.copy(learning = base.learning.copy(directInputStatus = "CLEANUP_UNKNOWN")) }
        rule.onNodeWithText("이전 음성 입력의 종료를 확인하고 있어요. 같은 질문을 유지해요").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("답변 시작").assertIsNotEnabled()
        rule.runOnIdle {
            val captureId = UUID.randomUUID()
            val current = requireNotNull(base.session.practiceModal)
            frame.value = base.copy(session = base.session.copy(inputPhase = InputPhase.CAPTURING,
                captureLease = CaptureLease(captureId, UUID.randomUUID(), owner, task, lessonId, 0, 0),
                practiceModal = current.copy(phase = PracticeModalPhase.CAPTURING, captureToken = captureId,
                    captureReturnState = PracticeModalPhase.QUESTION)),
                learning = base.learning.copy(directInputReady = true, directInputStatus = "INITIALIZING",
                    directInputNotice = "합성 입력 안내: 실제 마이크나 SDK 요청은 실행하지 않아요"))
        }
        val cancel = rule.onNodeWithText("답변 취소").assertIsDisplayed()
        assertTrue(cancel.fetchSemanticsNode().boundsInRoot.height >= 76f)
        cancel.performClick()
        assertTrue(intents.filterNot { it is UiIntent.SupportWitness }.last() is UiIntent.CancelAnswer)
        assertFalse(intents.any { it is UiIntent.StartAnswer || it is UiIntent.FinishAnswer || it is UiIntent.ReadHistory })

        rule.runOnIdle {
            val selected = player().copy(appearance = DeviceAppearance.DARK)
            frame.value = selected.copy(session = selected.session.copy(screen = SessionScreen.SETTINGS), draft = SettingsDraft(settings))
        }
        rule.onNode(isDialog()).assertDoesNotExist()
        for (value in listOf("기술", "영어 원문 전체", "추가 없이 듣기")) {
            rule.onNode(hasScrollAction()).performScrollToNode(hasText(value))
            rule.onNodeWithText(value, useUnmergedTree = true).assertIsDisplayed()
        }
        rule.onNodeWithText("설정 적용").assertIsDisplayed()
        capture("prepared-after-settings-current-values-narrow-large")
    }

    @Test fun currentSegmentFollowsInCenterButManualScrollWaitsForExplicitReturn() {
        val frame = mutableStateOf(player()); val intents = mutableListOf<UiIntent>()
        render(frame, intents)
        fun assertCentered(text: String) {
            val reader = rule.onNodeWithTag("focused-transcript").fetchSemanticsNode().boundsInRoot
            val current = rule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot
            assertTrue("Current segment remains centered", kotlin.math.abs(reader.center.y - current.center.y) <= 2f)
        }
        assertCentered(fragments[1])
        rule.onNodeWithTag("focused-transcript").performTouchInput { swipeUp() }
        rule.onNodeWithText("현재 구간으로").assertIsDisplayed()
        rule.runOnIdle {
            val snapshot = requireNotNull(frame.value.segmentPlayback.snapshot)
            frame.value = frame.value.copy(segmentPlayback = frame.value.segmentPlayback.copy(snapshot = snapshot.copy(
                cursor = cursor.copy(segmentId = manifest.segments[2].segmentId, segmentIndex = 2))))
        }
        rule.onNodeWithText("현재 구간으로").assertIsDisplayed().performClick()
        assertCentered(fragments[2])
        rule.onNodeWithText("현재 구간으로").assertDoesNotExist()
        capture("prepared-focused-reader-wide")
        rule.onNodeWithText("기사 메뉴").performClick()
        rule.onNodeWithText("문장 도움").assertIsDisplayed()
        rule.onNodeWithText("기사 정보").performClick()
        assertTrue(intents.single() is UiIntent.Navigate)
        assertEquals(SessionScreen.ARTICLE_INFO, (intents.single() as UiIntent.Navigate).screen)
    }

    @Test fun compactDarkLargeTextKeepsReaderArticleActionsAndCenteredTransport() {
        val frame = mutableStateOf(player().copy(appearance = DeviceAppearance.DARK)); val intents = mutableListOf<UiIntent>()
        render(frame, intents, width = 900, scale = 1.3f)
        rule.onNodeWithText("말하기 연습").assertIsDisplayed()
        rule.onNodeWithText("기사에 질문하기").assertIsDisplayed()
        rule.onNodeWithContentDescription("재생").assertIsDisplayed()
        val reader = rule.onNodeWithTag("focused-transcript").fetchSemanticsNode().boundsInRoot
        val play = rule.onNodeWithContentDescription("재생").fetchSemanticsNode().boundsInRoot
        assertTrue(kotlin.math.abs(reader.center.x - play.center.x) <= 2f)
        rule.onNodeWithTag("focused-transcript").performTouchInput { swipeUp() }
        rule.onNodeWithText("현재 구간으로").assertIsDisplayed()
        capture("prepared-focused-reader-narrow-dark-large", dialog = true)
        assertTrue(intents.isEmpty())
    }

    @Test fun samePlayerModalRendersQuestionThenFeedbackAndCloseOnlyFinishesPractice() {
        validateTypedSources()
        val frame = mutableStateOf(modal()); val intents = mutableListOf<UiIntent>()
        render(frame, intents)
        rule.onNodeWithTag("prepared-test-frame").assertExists()
        rule.onNodeWithText("같은 기사로 말하기").assertIsDisplayed()
        rule.onNodeWithText(question).assertIsDisplayed()
        rule.onNodeWithText("답변 시작").assertIsDisplayed()
        assertEquals(SessionScreen.PLAYER, frame.value.session.screen)
        assertTrue(intents.isEmpty())
        capture("prepared-modal-question", dialog = true)
        rule.runOnIdle { frame.value = modal(frame.value, feedback = true) }
        rule.onNodeWithText(question).assertIsDisplayed()
        rule.onNodeWithText("현재 판정").assertIsDisplayed()
        rule.onNodeWithText("합성 피드백: 두 지역을 연결한다는 내용을 담았어요").assertIsDisplayed()
        assertEquals(SessionScreen.PLAYER, frame.value.session.screen)
        capture("prepared-modal-feedback", dialog = true)
        rule.onNodeWithText("기사로 돌아가기").performClick()
        assertEquals(1, intents.size)
        assertTrue(intents.single() is UiIntent.FinishPractice)
        assertEquals(lessonId, intents.single().origin.lessonId)
        assertFalse(intents.any { it is UiIntent.Navigate || it is UiIntent.ReadHistory })
    }

    @Test fun segmentControlsKeepVehicleTargetsAndRestrictionsHideStaleDetails() {
        validateTypedSources()
        val frame = mutableStateOf(player()); val intents = mutableListOf<UiIntent>()
        render(frame, intents)
        rule.onNodeWithText(fragments[0]).assertIsDisplayed()
        listOf("이전 구간", "재생", "다음 구간").forEach { label ->
            val node = rule.onNodeWithContentDescription(label).assertIsEnabled()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue("$label touch width", bounds.width >= 76f)
            assertTrue("$label touch height", bounds.height >= 76f)
        }
        rule.onNodeWithContentDescription("이전 구간").performClick()
        rule.onNodeWithContentDescription("다음 구간").performClick()
        assertEquals(listOf(-1L, 1L), intents.filterIsInstance<UiIntent.Seek>().map { it.offsetMs })
        assertFalse(intents.any { it is UiIntent.StartPractice || it is UiIntent.StartAnswer })
        capture("prepared-segment-reader")
        rule.runOnIdle { frame.value = modal(frame.value) }
        rule.onNodeWithText(question).assertIsDisplayed()
        val driving = allowed.copy(safety = SafetyState(VehiclePosition.MOVING_OR_REVERSE_NEUTRAL, true, false, false))
        rule.runOnIdle { frame.value = frame.value.copy(session = frame.value.session.copy(screen = SessionScreen.SETTINGS, access = driving), draft = SettingsDraft(settings)) }
        rule.onNodeWithText(question).assertDoesNotExist()
        rule.onNodeWithText(fragments[0]).assertDoesNotExist()
        rule.onNodeWithText("설정 적용").assertDoesNotExist()
        rule.onNodeWithContentDescription("듣기 설정").assertDoesNotExist()
        rule.onNodeWithContentDescription("재생").assertIsNotEnabled()
        rule.runOnIdle { frame.value = frame.value.copy(session = frame.value.session.copy(access = allowed.copy(power = PowerAvailability.SUSPENDING))) }
        rule.onNodeWithText(question).assertDoesNotExist()
        rule.onNodeWithText(fragments[0]).assertDoesNotExist()
        rule.onNodeWithText("설정 적용").assertDoesNotExist()
        assertEquals(2, intents.size)
        capture("prepared-restricted-details")
        // A current parked projection with raw UNKNOWN can read/play with explicit
        // component/route evidence; it does not restore the previously restricted modal.
        rule.runOnIdle {
            val current = player()
            frame.value = current.copy(session = current.session.copy(access = allowed.copy(power = PowerAvailability.UNKNOWN)))
        }
        assertTrue(frame.value.canRead); assertTrue(frame.value.session.access.mediaReady)
        rule.onNodeWithText(fragments[0]).assertIsDisplayed()
        rule.onNodeWithText(question).assertDoesNotExist()
        rule.onNodeWithContentDescription("재생").assertIsEnabled()
        assertEquals(2, intents.size)
        capture("prepared-raw-unknown-readable")
    }

    @Test fun emptyPreparedCatalogStillUsesSixConfiguredTopicsWithoutLegacyTagUnion() {
        validateTypedSources()
        val irrelevantLegacy = CatalogArticle(UUID.randomUUID(), UUID.randomUUID(), "Unrelated legacy fixture", "Synthetic renderer fixture",
            URI("https://example.invalid/legacy"), "unknown", null, false, setOf("injected-legacy-tag"), 60_000, 60_000,
            Instant.parse("2026-11-14T00:00:00Z"), Instant.parse("2026-11-16T00:00:00Z"))
        val initial = player().let { it.copy(session = it.session.copy(screen = SessionScreen.SETTINGS), preparedCatalog = emptyList(),
            catalog = listOf(irrelevantLegacy), draft = SettingsDraft(settings)) }
        val frame = mutableStateOf(initial); val intents = mutableListOf<UiIntent>()
        render(frame, intents)
        rule.onNodeWithText("관심 분야").performClick()
        categories.categories.forEach { category ->
            rule.onNode(hasScrollAction()).performScrollToNode(hasText(category.displayName))
            rule.onNode(hasText(category.displayName) and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Checkbox)).assertIsDisplayed().performClick()
        }
        assertEquals(categories.categories.map { it.id }.toSet(), intents.filterIsInstance<UiIntent.Topic>().map { it.topic }.toSet())
        assertEquals(6, intents.size)
        rule.onNodeWithText("injected-legacy-tag").assertDoesNotExist()
        assertTrue(frame.value.preparedCatalog.isEmpty())
        // The same server projection may disable a topic already selected by this owner.
        rule.runOnIdle { frame.value = initial.copy(categoryConfig = categories.copy(categories = categories.categories.map {
            if (it.id == "culture") it.copy(active = false) else it }), draft = SettingsDraft(settings.copy(topics = listOf("technology", "culture")))) }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("문화"))
        rule.onNode(hasText("문화") and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Checkbox)).assertIsDisplayed().assertIsNotEnabled()
        rule.onNodeWithText("injected-legacy-tag").assertDoesNotExist()
        assertEquals(setOf("technology", "culture"), frame.value.draft?.topics)
        assertEquals(6, intents.size)
        capture("prepared-settings-configured-topics")
    }
    private fun captureConfirmation(phase: CaptureConfirmationPhase): ProductScreenState {
        val base=modal();val id=UUID.randomUUID();val confirmation=CaptureConfirmationView(id,UUID.randomUUID(),phase)
        val current=requireNotNull(base.session.practiceModal)
        return base.copy(session=base.session.copy(access=base.session.access.copy(directReplyFocusAllowed=true),inputPhase=InputPhase.FINALIZING,
            captureLease=CaptureLease(id,UUID.randomUUID(),owner,task,lessonId,0,0),captureConfirmation=confirmation,
            practiceModal=current.copy(phase=PracticeModalPhase.CAPTURING,captureToken=id,captureReturnState=PracticeModalPhase.QUESTION)))
    }
    @Test fun captureLanguageQuestionKeepsArticleAndQuestionAndOnlySendsScopedChoices() {
        val frame=mutableStateOf(captureConfirmation(CaptureConfirmationPhase.ASKING));val intents=mutableListOf<UiIntent>()
        render(frame,intents)
        rule.onNodeWithText(question).assertIsDisplayed()
        rule.onNodeWithText("방금 답변을 영어로 했나요?").assertIsDisplayed()
        val confirmation=requireNotNull(frame.value.session.captureConfirmation)
        for((label,choice) in listOf("영어로 답했어요" to CaptureLanguageChoice.ENGLISH,
            "한국어로 답했어요" to CaptureLanguageChoice.KOREAN,"잘 모르겠어요" to CaptureLanguageChoice.UNSURE)) {
            val button=rule.onNodeWithText(label).assertIsDisplayed()
            assertTrue(button.fetchSemanticsNode().boundsInRoot.height>=76f)
            button.performClick()
            val event=intents.last() as UiIntent.ConfirmCaptureLanguage
            assertEquals(choice,event.choice);assertEquals(confirmation.captureId,event.captureId)
            assertEquals(confirmation.confirmationToken,event.confirmationToken);assertEquals(frame.value.origin,event.origin)
        }
        rule.onNodeWithText("답변 시작").assertDoesNotExist()
        rule.onNodeWithText("힌트",useUnmergedTree=true).assertDoesNotExist()
        rule.onNodeWithText("저장 후 나가기").assertDoesNotExist()
        capture("prepared-capture-language-asking",dialog=true)
        rule.onNodeWithText("답변 취소").performClick();assertTrue(intents.last() is UiIntent.CancelAnswer)
    }
    @Test fun captureLanguageSendingAndExplicitRetryKeepSameIdentityWithoutShowingRecognizedQuote() {
        val initial=captureConfirmation(CaptureConfirmationPhase.SENDING)
        val frame=mutableStateOf(initial);val intents=mutableListOf<UiIntent>();render(frame,intents)
        rule.onNodeWithText("답변 언어를 확인하고 있어요").assertIsDisplayed()
        rule.onNodeWithText("영어로 답했어요").assertDoesNotExist()
        rule.onNodeWithText("확인 연결 다시 시도").assertDoesNotExist()
        assertTrue(intents.isEmpty())
        val confirmation=requireNotNull(initial.session.captureConfirmation)
        rule.runOnIdle { frame.value=initial.copy(appearance=DeviceAppearance.DARK,
            session=initial.session.copy(captureConfirmation=confirmation.copy(phase=CaptureConfirmationPhase.RETRYABLE))) }
        rule.onNodeWithText("확인 연결을 마치지 못했어요").assertIsDisplayed()
        rule.onNodeWithText(question).assertIsDisplayed()
        capture("prepared-capture-language-retry-dark",dialog=true)
        rule.onNodeWithText("확인 연결 다시 시도").performClick()
        val retry=intents.single() as UiIntent.RetryCaptureConfirmation
        assertEquals(confirmation.captureId,retry.captureId);assertEquals(confirmation.confirmationToken,retry.confirmationToken)
    }
    @Test fun captureLanguageControlsDisappearWhenParkOrWindowBecomesUnavailable() {
        val original=captureConfirmation(CaptureConfirmationPhase.ASKING)
        val frame=mutableStateOf(original);val intents=mutableListOf<UiIntent>();render(frame,intents)
        rule.onNodeWithText("방금 답변을 영어로 했나요?").assertIsDisplayed()
        rule.runOnIdle { frame.value=original.copy(session=original.session.copy(access=allowed.copy(
            safety=allowed.safety.copy(position=VehiclePosition.UNKNOWN,generation=1)))) }
        rule.onNodeWithText("방금 답변을 영어로 했나요?").assertDoesNotExist()
        rule.onNodeWithText("영어로 답했어요").assertDoesNotExist();assertTrue(intents.isEmpty())
    }

    @Test fun captureLanguageNarrowComponentWithLargeTextKeepsChoicesVisibleAndSeparateFromQuestion() {
        val base=captureConfirmation(CaptureConfirmationPhase.ASKING)
        val state=base.copy(appearance=DeviceAppearance.DARK,learning=base.learning.copy(practice=base.learning.practice!!.copy(questionEn=question.repeat(6))))
        val intents=mutableListOf<UiIntent>()
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f,1.3f)) {
                DriveEnglishTheme(state.appearance) {
                    Surface(Modifier.requiredSize(980.dp,1000.dp).testTag("prepared-test-frame")) {
                        PracticeScreen(state,{intents.add(it)},{})
                    }
                }
            }
        }
        for(label in listOf("영어로 답했어요","한국어로 답했어요","잘 모르겠어요","답변 취소")) {
            val node=rule.onNodeWithText(label);node.assertIsDisplayed()
            assertTrue(node.fetchSemanticsNode().boundsInRoot.height>=76f)
        }
        assertTrue(intents.isEmpty())
        capture("prepared-capture-language-narrow-large-text")
    }

}
