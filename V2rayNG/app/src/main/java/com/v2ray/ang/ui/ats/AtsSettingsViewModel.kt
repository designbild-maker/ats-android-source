package com.v2ray.ang.ui.ats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** ATS: the few settings shown on the simplified Settings tab. */
data class AtsSettingsState(
    val startOnBoot: Boolean = false,
)

/**
 * Owns the persisted values behind the ATS Settings tab. Everything else on that tab
 * opens existing screens, so this ViewModel stays intentionally small.
 */
class AtsSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(AtsSettingsState())
    val state: StateFlow<AtsSettingsState> = _state.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch(Dispatchers.IO) {
            val startOnBoot = MmkvManager.decodeStartOnBoot()
            _state.update { it.copy(startOnBoot = startOnBoot) }
        }
    }

    fun setStartOnBoot(enabled: Boolean) {
        _state.update { it.copy(startOnBoot = enabled) }
        viewModelScope.launch(Dispatchers.IO) {
            MmkvManager.encodeStartOnBoot(enabled)
        }
    }
}
