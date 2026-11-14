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
import io.github.driveenglishlab.pleos.data.wire.WireCommonSettings
import io.github.driveenglishlab.pleos.domain.*
import io.github.driveenglishlab.pleos.ui.*
import io.github.driveenglishlab.pleos.ui.theme.DriveEnglishTheme
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Renderer/intent fixtures only. Synthetic canLaunch is not actual Google configuration,
 * a credential, owner adoption or vehicle/power permission. No provider request is sent. */
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
    private fun render(state: State<ProductScreenState>, intents: MutableList<UiIntent>, panelOnly: Boolean = false) {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                DriveEnglishTheme(DeviceAppearance.DARK) {
                    Surface(Modifier.requiredSize(1200.dp, 1000.dp)) {
                        if (panelOnly) AccountEntryPanel(state.value, intents::add)
                        else NewsScreens(state.value, intents::add, {}, { _, _ -> })
                    }
                }
            }
        }
    }
    @Test fun settingsExposesExplicitGuestLinkMergeAndLinkedSwitchWithoutAutomaticIntent() {
        val state = mutableStateOf(settings()); val intents = mutableListOf<UiIntent>(); render(state, intents)
        assertTrue(intents.isEmpty())
        scrollButton("Google 계정 연결").assertIsEnabled().performClick()
        scrollButton("기존 계정과 기록 합치기").assertIsEnabled().performClick()
        assertEquals(listOf(AccountEntryAction.NEW_LINK, AccountEntryAction.EXISTING_MERGE),
            intents.filterIsInstance<UiIntent.AccountEntry>().map { it.action })
        assertTrue(intents.all { it.origin == state.value.origin })
        assertFalse(intents.any { it is UiIntent.Logout || it is UiIntent.DeleteHistory || it is UiIntent.Refresh })
        rule.runOnIdle { state.value = settings().copy(accountEntry = AccountEntryState(hasSession = true, linked = true, canLaunch = true)) }
        button("Google 계정 연결").assertDoesNotExist(); button("기존 계정과 기록 합치기").assertDoesNotExist()
        scrollButton("다른 Google 계정으로 전환").performClick()
        assertEquals(AccountEntryAction.SWITCH_LINKED, (intents.last() as UiIntent.AccountEntry).action)
    }
    @Test fun sourceLessRecoverySeparatesProtectedRefreshAndExplicitTargetRestore() {
        val state = mutableStateOf(recovery()); val intents = mutableListOf<UiIntent>(); render(state, intents)
        assertFalse(state.value.canRead); assertNull(state.value.session.owner); assertTrue(intents.isEmpty())
        scrollButton("보호된 연결 다시 확인").performClick()
        assertTrue(intents.single() is UiIntent.Refresh)
        scrollButton("연결한 계정으로 복원").assertIsEnabled().performClick()
        val restore = intents.last() as UiIntent.AccountEntry
        assertEquals(AccountEntryAction.RESTORE_LINKED, restore.action); assertNull(restore.origin.owner)
        button("Google 계정 연결").assertDoesNotExist(); button("기존 계정과 기록 합치기").assertDoesNotExist()
        rule.onNodeWithText("계정에 연결하지 않은 이전 미로그인 기록은 복구를 보장할 수 없어요", substring = true).assertIsDisplayed()
        val bounds = button("연결한 계정으로 복원").fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.width >= 76f); assertTrue(bounds.height >= 76f)
    }
    @Test fun missingPhysicalOrLaunchGateNeverEnablesSourceLessAuthentication() {
        val state = mutableStateOf(recovery()); val intents = mutableListOf<UiIntent>(); render(state, intents, panelOnly = true)
        val closed = listOf(
            access.copy(power = PowerAvailability.UNKNOWN), access.copy(power = PowerAvailability.DISALLOWED),
            access.copy(safety = access.safety.copy(position = VehiclePosition.DRIVE_STOPPED)),
            access.copy(safety = access.safety.copy(uxKnown = false)),
            access.copy(safety = access.safety.copy(detailsAllowed = false)),
            access.copy(safety = access.safety.copy(systemBlocked = true)),
            access.copy(safety = access.safety.copy(directInputAllowed = false)),
            access.copy(window = WindowState(true, false)), access.copy(window = WindowState(false, false)))
        for (gate in closed) {
            rule.runOnIdle { state.value = recovery().copy(session = recovery().session.copy(access = gate.copy(ownerValid = false))) }
            button("연결한 계정으로 복원").assertIsNotEnabled()
            assertTrue(intents.isEmpty())
        }
        for (busyVoice in listOf(VoiceInputActivity.CAPTURING, VoiceInputActivity.PROCESSING)) {
            rule.runOnIdle { state.value = recovery().copy(session = recovery().session.copy(voiceInput = busyVoice)) }
            button("연결한 계정으로 복원").assertIsNotEnabled(); assertTrue(intents.isEmpty())
        }
        rule.runOnIdle { state.value = recovery().copy(session = recovery().session.copy(inputPhase = InputPhase.CAPTURING)) }
        button("연결한 계정으로 복원").assertIsNotEnabled(); assertTrue(intents.isEmpty())
        rule.runOnIdle { state.value = recovery().copy(accountEntry = AccountEntryState(canLaunch = false)) }
        button("연결한 계정으로 복원").assertIsNotEnabled(); assertTrue(intents.isEmpty())
        rule.runOnIdle { state.value = settings().copy(session = settings().session.copy(access = access.copy(ownerValid = false))) }
        button("Google 계정 연결").assertIsNotEnabled(); button("기존 계정과 기록 합치기").assertIsNotEnabled()
        assertTrue(intents.isEmpty())
    }
    @Test fun pendingCancellationUnavailableAndTransitionRetryKeepTheirOriginalRoles() {
        val state = mutableStateOf(settings().copy(accountEntry = AccountEntryState(
            phase = AccountEntryPhase.AUTHENTICATING, action = AccountEntryAction.EXISTING_MERGE, hasSession = true, canLaunch = true)))
        val intents = mutableListOf<UiIntent>(); render(state, intents, panelOnly = true)
        button("Google 계정 연결").assertDoesNotExist(); button("기존 계정과 기록 합치기").assertDoesNotExist()
        assertTrue(intents.isEmpty()); button("계정 연결 취소").performClick()
        assertEquals(AccountEntryAction.CANCEL, (intents.single() as UiIntent.AccountEntry).action)
        rule.runOnIdle { state.value = settings().copy(accountEntry = AccountEntryState(
            phase = AccountEntryPhase.UNAVAILABLE, message = "Google 연결 제공자가 비활성이에요", hasSession = true, canLaunch = false)) }
        rule.onNodeWithText("Google 연결 제공자가 비활성이에요").assertIsDisplayed()
        button("Google 계정 연결").assertIsNotEnabled(); assertEquals(1, intents.size)
        rule.runOnIdle { state.value = settings().copy(accountEntry = AccountEntryState(
            phase = AccountEntryPhase.FAILED, hasSession = true, canRetryTransition = true)) }
        button("Google 계정 연결").assertDoesNotExist(); button("계정 전환 다시 확인").performClick()
        assertEquals(AccountEntryAction.RETRY_TRANSITION, (intents.last() as UiIntent.AccountEntry).action)
        rule.runOnIdle { state.value = state.value.copy(session = state.value.session.copy(access = access.copy(power = PowerAvailability.UNKNOWN)),
            accountEntry = state.value.accountEntry.copy(phase = AccountEntryPhase.AUTHENTICATING)) }
        button("계정 연결 취소").assertDoesNotExist(); assertEquals(2, intents.size)
    }
}
