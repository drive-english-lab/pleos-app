package io.github.driveenglishlab.pleos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.driveenglishlab.pleos.domain.OwnerBindingStatus
import io.github.driveenglishlab.pleos.ui.AccountEntryAction
import io.github.driveenglishlab.pleos.ui.AccountEntryPhase
import io.github.driveenglishlab.pleos.ui.ProductScreenState
import io.github.driveenglishlab.pleos.ui.UiIntent
import io.github.driveenglishlab.pleos.ui.accountEntryAllowed
import io.github.driveenglishlab.pleos.ui.components.ProductButton

/** Current storage status. No account controls are offered in R6. */
@Composable
internal fun AccountEntryPanel(state: ProductScreenState, send: (UiIntent) -> Unit) {
    if (io.github.driveenglishlab.pleos.data.CurrentAccountScope.accountActionsEnabled) {
        FutureAccountEntryPanel(state, send)
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("저장과 동기화", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() })
        Text(if (state.accountEntry.linked) "현재 연결된 프로필의 기록을 유지해요" else "현재 학습 프로필의 기록을 유지해요",
            style = MaterialTheme.typography.bodyMedium)
        Text("기기에 저장한 기록은 연결이 확인되면 같은 프로필로 서버와 동기화해요",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("같은 설치에서 식별정보가 유지되면 학습을 이어갈 수 있어요. 앱을 다시 설치하거나 차량을 바꿔 식별정보가 사라지면 기록 복구를 보장할 수 없어요",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("서버에 전송하지 못한 기록이 기기에서 사라지면 복구할 수 없어요",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Retained future renderer. The current product never admits its actions. */
/** Presentation only: launch is an explicit intent, never a credential or owner admission.
 * Uses the host's same current physical/input gate. Source-less restore omits ownerValid
 * only; it cannot relax vehicle/window/power or an in-progress voice/input restriction. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun FutureAccountEntryPanel(state: ProductScreenState, send: (UiIntent) -> Unit) {
    val entry = state.accountEntry
    val physicalInput = accountEntryAllowed(state.session)
    val sourceReady = entry.hasSession && state.session.owner != null && state.session.access.ownerValid &&
        state.session.ownerBinding == OwnerBindingStatus.VERIFIED
    val mayLaunch = physicalInput && entry.canLaunch && !entry.busy && !state.busy && !state.deletionPending
    val mayRetry = physicalInput && entry.canRetryTransition && !entry.busy && !state.deletionPending
    fun dispatch(action: AccountEntryAction) { send(UiIntent.AccountEntry(state.origin, action)) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(if (entry.hasSession) "계정 연결" else "연결한 계정으로 복원", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() })
        Text(when {
            !entry.hasSession -> "전에 연결한 Google 계정으로 서버에 저장된 기록만 불러와요. 계정에 연결하지 않은 이전 미로그인 기록은 복구를 보장할 수 없어요"
            entry.linked -> "다른 계정으로 전환하면 현재 계정의 기록과 자동으로 합치지 않아요. 다른 기기의 로그인과 기록은 유지돼요"
            else -> "로그인은 선택이에요. 처음 연결하면 이 차의 기록을 유지해요. 기존 앱 계정에 연결하면 양쪽 기록을 합치고, 이 차에서 직접 고른 설정과 진행 중 학습을 우선해요"
        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!physicalInput) Text("주차·차량 상태와 앱 창을 확인하고 진행 중인 입력을 마쳐 주세요",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val status = entry.message ?: when (entry.phase) {
            AccountEntryPhase.IDLE -> null
            AccountEntryPhase.CHECKING_PROVIDER -> "Google 계정 연결이 가능한지 확인하고 있어요"
            AccountEntryPhase.PREPARING -> "선택한 계정 연결을 준비하고 있어요"
            AccountEntryPhase.AUTHENTICATING -> "Google 계정 인증을 기다리고 있어요"
            AccountEntryPhase.VERIFYING -> "서버에서 계정과 기록을 확인하고 있어요"
            AccountEntryPhase.RECOVERING -> "진행 중이던 계정 전환을 확인하고 있어요"
            AccountEntryPhase.UNAVAILABLE -> "지금은 계정을 연결할 수 없어요. 기존 기록과 보호된 연결 상태는 유지돼요"
            AccountEntryPhase.CANCELLED -> "계정 인증을 취소했어요. 기존 기록은 유지돼요"
            AccountEntryPhase.FAILED -> "계정 연결을 마치지 못했어요. 기존 기록을 유지하고 다시 확인할 수 있어요"
            AccountEntryPhase.COMPLETE -> "서버에서 계정 연결을 확인했어요"
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        if (entry.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            if (entry.canCancel && physicalInput) ProductButton("계정 연결 취소", { dispatch(AccountEntryAction.CANCEL) }, secondary = true)
        } else FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (entry.canRetryTransition) {
                ProductButton("계정 전환 다시 확인", { dispatch(AccountEntryAction.RETRY_TRANSITION) }, enabled = mayRetry)
            } else when {
                !entry.hasSession -> ProductButton("연결한 계정으로 복원", { dispatch(AccountEntryAction.RESTORE_LINKED) },
                    enabled = mayLaunch, secondary = true)
                entry.linked -> ProductButton("다른 Google 계정으로 전환", { dispatch(AccountEntryAction.SWITCH_LINKED) },
                    enabled = mayLaunch && sourceReady, secondary = true)
                else -> {
                    ProductButton("Google 계정 연결", { dispatch(AccountEntryAction.NEW_LINK) }, enabled = mayLaunch && sourceReady, secondary = true)
                    ProductButton("기존 계정과 기록 합치기", { dispatch(AccountEntryAction.EXISTING_MERGE) }, enabled = mayLaunch && sourceReady, secondary = true)
                }
            }
            if (entry.phase in setOf(AccountEntryPhase.UNAVAILABLE, AccountEntryPhase.FAILED)) {
                ProductButton("계정 연결 취소", { dispatch(AccountEntryAction.CANCEL) }, enabled = physicalInput, secondary = true)
            }
        }
    }
}
