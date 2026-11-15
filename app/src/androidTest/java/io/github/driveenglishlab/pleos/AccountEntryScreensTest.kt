package io.github.driveenglishlab.pleos

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import io.github.driveenglishlab.pleos.data.wire.WireCommonSettings
import io.github.driveenglishlab.pleos.domain.*
import io.github.driveenglishlab.pleos.ui.*
import io.github.driveenglishlab.pleos.ui.theme.DriveEnglishTheme
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** R6 renderer/admission fixtures. Synthetic access never changes actual vehicle power or identity. */
class AccountEntryScreensTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val owner = OwnerContext(UUID.randomUUID(), 0, 1, UUID.randomUUID())
    private val access = AccessState(SafetyState(VehiclePosition.PARKED, true, true, true),
        WindowState(true, true), PowerAvailability.ALLOWED, ownerValid = true)
    private fun settings() = ProductScreenState(
        session = SessionView(screen = SessionScreen.SETTINGS, owner = owner,
            ownerBinding = OwnerBindingStatus.VERIFIED, access = access),
        draft = SettingsDraft(WireCommonSettings(listOf("technology"), "original_full", "none", emptyList(), true, 1)),
        accountEntry = AccountEntryState(hasSession = true, canLaunch = true))
    private fun recovery() = ProductScreenState(
        session = SessionView(screen = SessionScreen.RECOVERY, access = access.copy(ownerValid = false)),
        recovery = "저장된 연결을 보호하고 있어요", accountEntry = AccountEntryState(canLaunch = true))
    private fun button(label: String) = rule.onNode(hasText(label) and hasClickAction())
    private fun scrollButton(label: String): SemanticsNodeInteraction {
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(label) and hasClickAction())
        return button(label)
    }
    private fun render(state: State<ProductScreenState>, intents: MutableList<UiIntent>, panelOnly: Boolean = false, width: Int = 1200, fontScale: Float = 1f, appearance: DeviceAppearance = DeviceAppearance.DARK) {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                DriveEnglishTheme(appearance) {
                    Surface(Modifier.requiredSize(width.dp, 1000.dp)) {
                        if (panelOnly) AccountEntryPanel(state.value, intents::add)
                        else NewsScreens(state.value, intents::add, {}, { _, _ -> })
                    }
                }
            }
        }
        rule.waitUntil(timeoutMillis = 15_000) {
            runCatching { rule.onAllNodes(isRoot()).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
        }
    }
    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("r6Captures") != "true") return
        rule.waitForIdle()
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "r6-scope-captures").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { output ->
            rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
        }
    }
    private fun assertNoAccountControls() {
        listOf("Google 계정 연결", "기존 계정과 기록 합치기", "다른 Google 계정으로 전환",
            "연결한 계정으로 복원", "계정 전환 다시 확인", "계정 연결 취소", "이 차량에서 로그아웃")
            .forEach { button(it).assertDoesNotExist() }
    }
    @Test fun currentSettingsKeepStorageAndDeletionWithoutAnyAccountEntryForAnonymousOrLinked() {
        val state = mutableStateOf(settings()); val intents = mutableListOf<UiIntent>(); render(state, intents)
        scrollButton("학습 기록 삭제").assertIsEnabled()
        assertNoAccountControls(); assertTrue(intents.isEmpty())
        rule.onNodeWithText("저장과 동기화").assertExists()
        rule.runOnIdle { state.value = settings().copy(profileLabel = "연결된 프로필의 학습",
            accountEntry = AccountEntryState(hasSession = true, linked = true, canLaunch = true)) }
        scrollButton("학습 기록 삭제").assertIsEnabled()
        assertNoAccountControls()
        rule.onNodeWithText("현재 연결된 프로필의 기록을 유지해요").assertExists()
        capture("settings-linked-dark")
        scrollButton("학습 기록 삭제").performClick()
        rule.onNodeWithText("학습 기록을 삭제할까요?").assertIsDisplayed()
        rule.onNodeWithText("현재 연결과 관심 설정은 유지돼요", substring = true).assertIsDisplayed()
        rule.onNode(hasText("학습 기록 삭제") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        assertTrue(intents.single() is UiIntent.DeleteHistory)
        assertEquals(state.value.origin, intents.single().origin)
    }
    @Test fun sourceLessRecoveryOnlyRechecksTheSameProtectedConnectionAndExplainsLimits() {
        val state = mutableStateOf(recovery()); val intents = mutableListOf<UiIntent>(); render(state, intents)
        scrollButton("보호된 연결 다시 확인").performClick()
        assertTrue(intents.single() is UiIntent.Refresh)
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("저장과 동기화"))
        assertNoAccountControls()
        rule.onNodeWithText("식별정보가 사라지면 기록 복구를 보장할 수 없어요", substring = true).assertExists()
        rule.onNodeWithText("서버에 전송하지 못한 기록이 기기에서 사라지면 복구할 수 없어요").assertExists()
        capture("protected-recovery-dark")
    }
    @Test fun oldBusyPendingAndLaunchProjectionsNeverReintroduceAccountControlsUnderAnyPhysicalGate() {
        val state = mutableStateOf(recovery()); val intents = mutableListOf<UiIntent>(); render(state, intents, panelOnly = true)
        for (phase in AccountEntryPhase.entries) {
            for (gate in listOf(access, access.copy(power = PowerAvailability.UNKNOWN),
                access.copy(safety = access.safety.copy(position = VehiclePosition.DRIVE_STOPPED)),
                access.copy(window = WindowState(false, false)))) {
                rule.runOnIdle { state.value = recovery().copy(session = recovery().session.copy(access = gate),
                    accountEntry = AccountEntryState(phase = phase, message = "과거 Google 연결 상태", hasSession = true,
                        canLaunch = true, canRetryTransition = true)) }
                assertNoAccountControls()
                rule.onNodeWithText("과거 Google 연결 상태").assertDoesNotExist()
                rule.onAllNodes(hasClickAction()).assertCountEquals(0)
                assertTrue(intents.isEmpty())
            }
        }
    }
    @Test fun narrowLargeTypeAndUnknownVehicleKeepProtectedRecoveryWithoutAccountPlaceholders() {
        val state = mutableStateOf(recovery().copy(session = recovery().session.copy(
            access = access.copy(power = PowerAvailability.UNKNOWN, ownerValid = false,
                safety = access.safety.copy(position = VehiclePosition.DRIVE_STOPPED)))))
        val intents = mutableListOf<UiIntent>()
        render(state, intents, width = 900, fontScale = 1.3f, appearance = DeviceAppearance.LIGHT)
        scrollButton("보호된 연결 다시 확인").assertIsEnabled()
        val bounds = button("보호된 연결 다시 확인").fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.width >= 76f); assertTrue(bounds.height >= 76f)
        assertNoAccountControls(); assertTrue(intents.isEmpty())
        capture("protected-narrow-large-type-unknown-light")
    }
    @Test fun heldKnownOwnerHidesCachedRestoredAndSettingsUntilTheSameConnectionIsVerified() {
        val state = mutableStateOf(settings().copy(
            session = settings().session.copy(access = access.copy(ownerValid = false)),
            recovery = protectedConnectionMessage("OWNER_RECHECK_REQUIRED"),
            identityRecovery = IdentityRecoveryProjection(owner, IdentityRecoveryState.RESTORED,
                IdentitySyncState.SERVER_CONFIRMED, retrySameIdentity = true)))
        val intents = mutableListOf<UiIntent>(); render(state, intents)
        assertNoAccountControls()
        button("학습 기록 삭제").assertDoesNotExist()
        rule.onNodeWithText("서버에 저장된 기록이 연결됐어요", substring = true).assertDoesNotExist()
        scrollButton("보호된 연결 다시 확인").assertIsEnabled().performClick()
        assertTrue(intents.single() is UiIntent.Refresh)
    }
    @Test fun staleDirectAccountAndLogoutIntentsAreDeniedBeforeTheirEffectsButStorageActionsRemainCurrent() {
        val origin = settings().origin
        for (action in AccountEntryAction.entries) {
            assertFalse(currentProductIntentAllowed(UiIntent.AccountEntry(origin, action)))
        }
        assertFalse(currentProductIntentAllowed(UiIntent.Logout(origin)))
        assertTrue(currentProductIntentAllowed(UiIntent.Refresh(origin)))
        assertTrue(currentProductIntentAllowed(UiIntent.DeleteHistory(origin)))
        assertTrue(currentProductIntentAllowed(UiIntent.RetryDeletion(origin)))
        assertTrue(currentProductIntentAllowed(UiIntent.Stop(origin)))
        assertFalse(accountEntryAllowed(settings().session))
    }
}
