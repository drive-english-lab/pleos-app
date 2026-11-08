package io.github.driveenglishlab.pleos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import io.github.driveenglishlab.pleos.domain.ProbeStatus
import java.text.DateFormat
import java.util.Date
import kotlinx.serialization.Serializable

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    SetupNavigation()
                }
            }
        }
    }
}

@Serializable
private object Overview

@Serializable
private object Diagnostics

@Composable
private fun SetupNavigation() {
    val navController = rememberNavController()
    NavHost(navController, startDestination = Overview) {
        composable<Overview> {
            Column(
                modifier = Modifier.padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Pleos 개발 환경", style = MaterialTheme.typography.headlineMedium)
                Text("이 기기에서 Supabase와 Pleos SDK 연결 상태를 확인할 수 있습니다.")
                Button(onClick = { navController.navigate(Diagnostics) }) { Text("연결 점검 열기") }
            }
        }
        composable<Diagnostics> {
            DiagnosticsScreen(onBack = { navController.popBackStack() })
        }
    }
}

@Composable
private fun DiagnosticsScreen(
    onBack: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lastCheckedAt by viewModel.lastCheckedAt.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("연결 점검", style = MaterialTheme.typography.headlineMedium)
        Text("Supabase · ${statusLabel(state.supabase)}")
        Text("Vehicle · ${statusLabel(state.pleos?.vehicle)}")
        Text("NaviHelper · ${statusLabel(state.pleos?.navigation)}")
        Text("FusedLocation 지원 · ${statusLabel(state.pleos?.locationSupport)}")
        lastCheckedAt?.let { millis ->
            Text("최근 점검 · ${DateFormat.getDateTimeInstance().format(Date(millis))}")
        }
        if (!state.checkTimeSaved) Text("점검 시간을 저장할 수 없습니다.")
        Button(onClick = viewModel::checkConnections, enabled = !state.checking) {
            Text(if (state.checking) "점검 중" else "점검 실행")
        }
        TextButton(onClick = onBack) { Text("뒤로") }
    }
}

private fun statusLabel(status: ProbeStatus?) = when (status) {
    null -> "대기"
    ProbeStatus.READY -> "정상"
    ProbeStatus.NOT_CONFIGURED -> "설정 필요"
    ProbeStatus.UNSUPPORTED -> "미지원"
    ProbeStatus.PERMISSION_REQUIRED -> "권한 필요"
    ProbeStatus.TIMED_OUT -> "응답 시간 초과"
    ProbeStatus.FAILED -> "연결 실패"
}
