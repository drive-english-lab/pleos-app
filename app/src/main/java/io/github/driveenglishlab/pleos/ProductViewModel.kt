package io.github.driveenglishlab.pleos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.driveenglishlab.pleos.ui.UiIntent
import javax.inject.Inject
import kotlinx.coroutines.launch

@UnstableApi
@HiltViewModel
class ProductViewModel @Inject constructor(private val host: ProductSessionHost) : ViewModel() {
    val state = host.state
    init { host.start() }
    fun send(intent: UiIntent) { viewModelScope.launch { host.handle(intent) } }
}
