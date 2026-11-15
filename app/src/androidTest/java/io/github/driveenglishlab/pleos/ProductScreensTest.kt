package io.github.driveenglishlab.pleos

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
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
import io.github.driveenglishlab.pleos.data.wire.WireCommonSettings
import io.github.driveenglishlab.pleos.domain.*
import io.github.driveenglishlab.pleos.ui.*
import io.github.driveenglishlab.pleos.ui.theme.DriveEnglishTheme
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test

/** Renderer/intent fixtures only. They neither grant actual power nor publish news. */
class ProductScreensTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val owner = OwnerContext(UUID.randomUUID(), 0, 1, UUID.randomUUID())
    private val content = ContentRef(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), ListeningMode.ORIGINAL_FULL)
    private val asset = UUID.randomUUID()
    private val work = ListeningWorkRef(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), content,
        ListeningStage.MAIN, asset, 60_000, UUID.randomUUID(), 1)
    // Explicit synthetic component/source/route evidence. Raw AWAKE is never approval.
    private val component = ComponentAccess(known = true, allowed = true, sourceGeneration = 0)
    private val confirmedComponents = ComponentPolicies(generation = 0, audio = component,
        media = component, microphone = component)
    private val allowed = AccessState(SafetyState(VehiclePosition.PARKED, true, true, true), WindowState(true, true),
        PowerAvailability.ALLOWED, true, true, true, true, true,
        components = confirmedComponents, mediaRouteAvailable = true, mediaFocusAllowed = true,
        backgroundMediaAllowed = true, gleoRouteAvailable = true, gleoVehicleAllowed = true,
        parkedOutputAvailable = true)
    private val settings = WireCommonSettings(listOf("technology"), "original_full", "none", emptyList(), true, 1)
    private val script = PublishedScript(content.listeningScriptId, content.bundleId, CatalogScriptRole.ORIGINAL_FULL, null,
        (1..20).joinToString("\n\n") { "Paragraph $it. This synthetic article tests readable text and preserved controls." } + "\n\nLast sentence remains above the playback dock.",
        "a".repeat(64), asset, 60_000)
    private fun player() = ProductScreenState(session = SessionView(screen = SessionScreen.PLAYER, owner = owner,
        ownerBinding = OwnerBindingStatus.VERIFIED, access = allowed, listening = work, mediaConnected = true,
        mediaPositionMs = 12_000), reader = ReaderPresentation(script, ReaderLanguage.ENGLISH), readerStatus = LoadStatus.READY)
    private data class Frame(val state: ProductScreenState, val width: Int = 1600, val height: Int = 1100, val scale: Float = 1f)
    private fun render(frame: State<Frame>, intents: MutableList<UiIntent> = mutableListOf()) {
        rule.setContent {
            val f = frame.value
            CompositionLocalProvider(LocalDensity provides Density(1f, f.scale)) {
                DriveEnglishTheme(f.state.appearance) {
                    Surface(Modifier.requiredSize(f.width.dp, f.height.dp).testTag("frame")) {
                        NewsScreens(f.state, { intents.add(it) }, {}, { _, _ -> })
                    }
                }
            }
        }
    }
    private fun capture(name: String, fullDisplay: Boolean = false) {
        rule.waitForIdle()
        val bitmap = if (fullDisplay) requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            else rule.onNodeWithTag("frame").captureToImage().asAndroidBitmap()
        val directory = File(requireNotNull(rule.activity.getExternalFilesDir(null)), "ui-captures").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // Multi-user production AVDs do not expose user10's scoped external storage to adb.
    // Copy only these synthetic captures into the debuggable app's private files for run-as.
    @After fun exportSyntheticCaptures() {
        val source = File(requireNotNull(rule.activity.getExternalFilesDir(null)), "ui-captures")
        val target = File(rule.activity.filesDir, "ui-captures").apply { mkdirs() }
        source.listFiles()?.filter { it.isFile && it.extension == "png" }?.forEach { it.copyTo(File(target, it.name), overwrite = true) }
    }

    @Test fun rawUnknownKeepsParkedReaderButMissingComponentEvidenceBlocksNewPlayback() {
        val state = player().let { it.copy(session = it.session.copy(access = allowed.copy(
            power = PowerAvailability.UNKNOWN, components = ComponentPolicies()))) }
        val frame = mutableStateOf(Frame(state)); val intents = mutableListOf<UiIntent>()
        render(frame, intents)
        assertEquals(PowerAvailability.UNKNOWN, state.session.access.power)
        assertTrue(state.canRead); assertFalse(state.session.access.mediaReady)
        rule.onNodeWithText("Paragraph 1.", substring = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("재생").assertIsNotEnabled()
        assertTrue(intents.isEmpty())
        capture("reader-raw-unknown-without-component-approval")
        rule.runOnIdle { frame.value = Frame(state.copy(session = state.session.copy(intent = PlaybackIntent.PLAY))) }
        // Stop/pause remains available even when a fresh playback admission is unavailable.
        rule.onNodeWithContentDescription("일시정지").assertIsEnabled().performClick()
        assertFalse((intents.single() as UiIntent.PlayPause).wantPlay)
    }

    @Test fun rawUnknownWithExplicitComponentAndRouteEvidenceKeepsReaderAndMediaIntent() {
        val state = player().let { it.copy(session = it.session.copy(access = allowed.copy(power = PowerAvailability.UNKNOWN))) }
        val intents = mutableListOf<UiIntent>(); render(mutableStateOf(Frame(state)), intents)
        assertTrue(state.canRead); assertTrue(state.session.access.mediaReady)
        assertEquals(PowerAvailability.UNKNOWN, state.session.access.power)
        rule.onNodeWithText("Paragraph 1.", substring = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("재생").assertIsEnabled().performClick()
        assertEquals(1, intents.size)
        assertTrue((intents.single() as UiIntent.PlayPause).wantPlay)
        assertEquals(state.origin, intents.single().origin)
    }

    @Test fun missingDeniedStaleOrMissingRouteEvidenceBlocksMediaWithoutClosingDetails() {
        val frame = mutableStateOf(Frame(player())); val intents = mutableListOf<UiIntent>()
        render(frame, intents)
        val denied = component.copy(allowed = false)
        val unavailable = listOf(
            allowed.copy(components = confirmedComponents.copy(audio = ComponentAccess())),
            allowed.copy(components = confirmedComponents.copy(media = ComponentAccess())),
            allowed.copy(components = confirmedComponents.copy(audio = denied)),
            allowed.copy(components = confirmedComponents.copy(media = denied)),
            allowed.copy(components = confirmedComponents.copy(generation = 1)),
            allowed.copy(mediaRouteAvailable = false))
        for (access in unavailable) {
            val state = player().let { it.copy(session = it.session.copy(access = access)) }
            rule.runOnIdle { frame.value = Frame(state) }
            assertTrue(state.canRead); assertFalse(access.mediaReady)
            // Even known AWAKE does not supply a missing/denied/stale policy or route.
            assertEquals(PowerAvailability.ALLOWED, access.power)
            rule.onNodeWithText("Paragraph 1.", substring = true).assertIsDisplayed()
            rule.onNodeWithContentDescription("재생").assertIsNotEnabled()
            assertTrue(intents.isEmpty())
        }
    }

    @Test fun microphoneOnlyDenialKeepsDetailsAndMediaWithoutStartingCapture() {
        val access = allowed.copy(power = PowerAvailability.UNKNOWN,
            components = confirmedComponents.copy(microphone = component.copy(allowed = false)))
        val state = player().let { it.copy(session = it.session.copy(access = access)) }
        val intents = mutableListOf<UiIntent>(); render(mutableStateOf(Frame(state)), intents)
        assertTrue(state.canRead); assertTrue(access.mediaReady); assertFalse(access.canCapture)
        rule.onNodeWithText("Paragraph 1.", substring = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("재생").assertIsEnabled()
        rule.onNodeWithText("한국어").performClick()
        assertTrue(intents.single() is UiIntent.ReaderView)
        assertFalse(intents.any { it is UiIntent.StartAnswer || it is UiIntent.FinishAnswer || it is UiIntent.StartPractice })
    }

    @Test fun actualParkUxWindowOwnerAndSuspendRestrictionsStillCloseDetails() {
        val frame = mutableStateOf(Frame(player())); val intents = mutableListOf<UiIntent>()
        render(frame, intents)
        val closed = listOf(
            allowed.copy(safety = allowed.safety.copy(position = VehiclePosition.DRIVE_STOPPED)),
            allowed.copy(safety = allowed.safety.copy(uxKnown = false)),
            allowed.copy(safety = allowed.safety.copy(detailsAllowed = false)),
            allowed.copy(safety = allowed.safety.copy(systemBlocked = true)),
            allowed.copy(window = WindowState(false, false)), allowed.copy(ownerValid = false),
            allowed.copy(power = PowerAvailability.SUSPENDING), allowed.copy(power = PowerAvailability.SUSPENDED))
        for (access in closed) {
            val state = player().let { it.copy(session = it.session.copy(access = access)) }
            rule.runOnIdle { frame.value = Frame(state) }
            assertFalse(state.canRead)
            rule.onNodeWithText("Paragraph 1.", substring = true).assertDoesNotExist()
            assertTrue(intents.isEmpty())
        }
    }

    @Test fun readerLanguageAndPracticeKeepTypedIntentsWithoutLegacyTimeSeek() {
        val intents = mutableListOf<UiIntent>(); render(mutableStateOf(Frame(player())), intents)
        rule.onNodeWithText("한국어").performClick()
        rule.onNodeWithContentDescription("다음 구간").assertIsNotEnabled()
        assertEquals(1, intents.size)
        assertEquals(ReaderLanguage.KOREAN, (intents[0] as UiIntent.ReaderView).language)
        assertFalse(intents.any { it is UiIntent.Seek })
        assertFalse(intents.any { it is UiIntent.StartPractice || it is UiIntent.StartAnswer || it is UiIntent.FinishAnswer })
        // This is now an implemented action. A deliberate tap requests the existing
        // practice flow, without implicitly starting capture or submitting an answer.
        rule.onNodeWithText("말하기 연습").assertIsEnabled().performClick()
        assertEquals(2, intents.size)
        assertTrue(intents[1] is UiIntent.StartPractice)
        assertEquals(owner, intents[1].origin.owner)
        assertEquals(work, intents[1].origin.work)
        assertFalse(intents.any { it is UiIntent.StartAnswer || it is UiIntent.FinishAnswer })
        capture("reader-wide-light")
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(SessionScreen.NEWS, (intents.last() as UiIntent.Navigate).screen)
    }

    @Test fun dirtySettingsRequireAnExplicitDiscardAndKeepApplySeparate() {
        val intents = mutableListOf<UiIntent>()
        val state = player().copy(session = player().session.copy(screen = SessionScreen.SETTINGS),
            draft = SettingsDraft(settings).mode("briefing"))
        render(mutableStateOf(Frame(state)), intents)
        rule.onNodeWithText("뉴스", useUnmergedTree = false).performClick()
        rule.onNodeWithText("적용하지 않은 설정을 취소할까요?").assertIsDisplayed()
        assertTrue(intents.isEmpty())
        rule.onNodeWithText("계속 편집").performClick()
        rule.onNodeWithText("설정 적용").assertIsDisplayed()
        rule.onNodeWithText("뉴스").performClick()
        rule.onNodeWithText("변경 취소").performClick()
        assertTrue(intents.single() is UiIntent.CancelSettings)
        capture("settings-after-discard-intent")
    }

    @Test fun narrowDarkLargeTextAndEmptyStateKeepControlsAndLastParagraphReachable() {
        val frame = mutableStateOf(Frame(player().copy(appearance = DeviceAppearance.DARK), width = 900, height = 1100, scale = 1.3f))
        render(frame)
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Last sentence remains above the playback dock."))
        rule.onNodeWithText("Last sentence remains above the playback dock.").assertIsDisplayed()
        rule.onNodeWithContentDescription("재생").assertIsDisplayed()
        rule.onNodeWithContentDescription("이전 구간").assertIsDisplayed()
        capture("reader-narrow-dark-large-text", fullDisplay = true)
        rule.runOnIdle { frame.value = Frame(player().copy(session = player().session.copy(screen = SessionScreen.NEWS),
            appearance = DeviceAppearance.DARK, catalogStatus = LoadStatus.READY), width = 900, height = 1100, scale = 1.3f) }
        // Keep the failure evidence even if a future layout change clips the state.
        // The old title no longer exists in the production APK; distinguish absent
        // copy from an existing-but-clipped node before verifying visibility.
        capture("news-empty-narrow-before-assert", fullDisplay = true)
        rule.onNodeWithText("이 분야에 이용할 뉴스가 없어요").assertExists().assertIsDisplayed()
        rule.onNodeWithText("관심 분야를 확인하거나 나중에 새 뉴스를 확인해 주세요").assertIsDisplayed()
        rule.onNodeWithText("새 뉴스 듣기").assertIsDisplayed()
        rule.onNodeWithText("이어서 듣기").assertIsDisplayed()
        capture("news-empty-narrow", fullDisplay = true)
    }
}
