package com.v2ray.ang.ui.ats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Result of the last admin PIN check, shown in the PIN dialog. */
enum class AtsPinStatus { Idle, Checking, Wrong }

/** ATS: the few settings shown on the simplified Settings tab. */
data class AtsSettingsState(
    val startOnBoot: Boolean = false,
    /** True once the admin PIN was entered in this app session ("Advanced settings" open). */
    val adminUnlocked: Boolean = false,
    val pinStatus: AtsPinStatus = AtsPinStatus.Idle,
    /** False when this build has no admin PIN (e.g. built from the public source copy). */
    val adminPinConfigured: Boolean = BuildConfig.ADMIN_PIN_HASH.isNotEmpty(),
)

/**
 * Owns the persisted values behind the ATS Settings tab and the admin PIN check that
 * unlocks the full v2rayNG interface. Everything else on that tab opens existing screens.
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

    fun resetPinStatus() {
        _state.update { it.copy(pinStatus = AtsPinStatus.Idle) }
    }

    /** Checks [pin] against the build's admin PIN hash; on success unlocks for this session. */
    fun submitAdminPin(pin: String) {
        if (_state.value.pinStatus == AtsPinStatus.Checking) return
        _state.update { it.copy(pinStatus = AtsPinStatus.Checking) }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.Default) { matchesAdminPin(pin) }
            _state.update {
                if (ok) it.copy(adminUnlocked = true, pinStatus = AtsPinStatus.Idle)
                else it.copy(pinStatus = AtsPinStatus.Wrong)
            }
        }
    }

    companion object {
        // Must match atsAdminPinHash() in app/build.gradle.kts.
        private const val SALT = "ats-admin-pin-v1"
        private const val ITERATIONS = 20000
        private const val KEY_BITS = 256

        internal fun hashPin(pin: String): String {
            val spec = PBEKeySpec(pin.trim().toCharArray(), SALT.toByteArray(), ITERATIONS, KEY_BITS)
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
            return bytes.joinToString("") { "%02x".format(it) }
        }

        internal fun matchesAdminPin(pin: String, expectedHash: String = BuildConfig.ADMIN_PIN_HASH): Boolean {
            if (expectedHash.isEmpty() || pin.isBlank()) return false
            return MessageDigest.isEqual(hashPin(pin).toByteArray(), expectedHash.toByteArray())
        }
    }
}
