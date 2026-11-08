package io.github.driveenglishlab.pleos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.driveenglishlab.pleos.domain.ProbeStatus
import io.github.driveenglishlab.pleos.domain.SetupRepository
import io.github.driveenglishlab.pleos.platform.PleosProbe
import io.github.driveenglishlab.pleos.platform.PleosProbeResult
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SetupState(
    val checking: Boolean = false,
    val supabase: ProbeStatus? = null,
    val pleos: PleosProbeResult? = null,
    val checkTimeSaved: Boolean = true,
)

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val repository: SetupRepository,
    private val pleosProbe: PleosProbe,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SetupState())
    val state = mutableState.asStateFlow()

    val lastCheckedAt = repository.lastCheckedAt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun checkConnections() {
        if (mutableState.value.checking) return

        mutableState.value = SetupState(checking = true)
        viewModelScope.launch {
            val supabase = async { repository.checkSupabase() }
            val pleos = pleosProbe.check()
            val supabaseStatus = supabase.await()

            val saved = try {
                repository.saveCheckTime(System.currentTimeMillis())
                true
            } catch (_: IOException) {
                false
            }
            mutableState.value = SetupState(
                supabase = supabaseStatus,
                pleos = pleos,
                checkTimeSaved = saved,
            )
        }
    }
}
