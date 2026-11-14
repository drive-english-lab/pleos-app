package io.github.driveenglishlab.pleos

import android.os.Bundle
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.UUID
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.media3.common.util.UnstableApi
import androidx.lifecycle.lifecycleScope
import io.github.driveenglishlab.pleos.platform.WindowAccessSource
import io.github.driveenglishlab.pleos.ui.theme.DriveEnglishTheme
import javax.inject.Inject
import kotlinx.coroutines.launch
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
@UnstableApi
class MainActivity : ComponentActivity() {
    @Inject lateinit var host: ProductSessionHost
    private val windowToken = UUID.randomUUID()
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        lifecycleScope.launch { host.microphonePermissionResult() }
    }
    private lateinit var windowAccess: WindowAccessSource

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        host.bindAccountActivity(this)
        windowAccess = WindowAccessSource(this)
        lifecycleScope.launch { host.attachWindow(windowToken) }
        setContent {
            val theme by host.appearances.state.collectAsStateWithLifecycle()
            DriveEnglishTheme(theme) {
                Surface(Modifier.fillMaxSize()) {
                    AppNavigation(host, windowToken, intent.getStringExtra(EXTRA_DESTINATION) == DIAGNOSTICS, requestMicrophone = { microphonePermission.launch(android.Manifest.permission.RECORD_AUDIO) }) { origin, uri ->
                        lifecycleScope.launch {
                            if (host.canOpenSource(origin, uri)) try {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri.toString())))
                            } catch (_: Exception) { host.sourceUnavailable() }
                        }
                    }
                }
            }
        }
    }

    private fun refreshWindow() {
        if (!::windowAccess.isInitialized) return
        windowAccess.refresh()
        val value = windowAccess.state.value
        lifecycleScope.launch { host.updateWindow(windowToken, value) }
    }
    override fun onStart() { super.onStart(); refreshWindow() }
    override fun onResume() { super.onResume(); refreshWindow() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); refreshWindow() }
    override fun onStop() {
        if (::windowAccess.isInitialized) {
            windowAccess.closed()
            val value = windowAccess.state.value
            lifecycleScope.launch { host.updateWindow(windowToken, value) }
        }
        super.onStop()
    }
    override fun onDestroy() { host.unbindAccountActivity(this); super.onDestroy() }
    companion object {
        const val EXTRA_DESTINATION = "io.github.driveenglishlab.pleos.DESTINATION"
        const val DIAGNOSTICS = "diagnostics"
        fun diagnosticsIntent(context: Context) = Intent(context, MainActivity::class.java).putExtra(EXTRA_DESTINATION, DIAGNOSTICS)
    }
}

@Serializable private object ProductDestination
@Serializable private object DiagnosticsDestination

@UnstableApi
@Composable
private fun AppNavigation(host: ProductSessionHost, windowToken: UUID, diagnostics: Boolean, requestMicrophone: () -> Unit,
    openSource: (io.github.driveenglishlab.pleos.ui.UiOrigin, java.net.URI) -> Unit) {
    val navigation = rememberNavController()
    NavHost(navigation, startDestination = if (diagnostics) DiagnosticsDestination else ProductDestination) {
        composable<ProductDestination> {
            val model: ProductViewModel = hiltViewModel()
            val state by model.state.collectAsStateWithLifecycle()
            DisposableEffect(host, windowToken) {
                host.surfaceVisible(windowToken, true)
                onDispose { host.surfaceVisible(windowToken, false) }
            }
            NewsScreens(state, model::send, onDiagnostics = { navigation.navigate(DiagnosticsDestination) }, onOpenSource = openSource, onMicrophonePermission = requestMicrophone)
        }
        composable<DiagnosticsDestination> {
            DisposableEffect(host, windowToken) {
                host.surfaceVisible(windowToken, false)
                onDispose { }
            }
            DiagnosticsScreen(onBack = { if (!navigation.popBackStack()) navigation.navigate(ProductDestination) })
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
