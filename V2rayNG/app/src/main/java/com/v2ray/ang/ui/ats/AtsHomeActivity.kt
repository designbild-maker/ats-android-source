package com.v2ray.ang.ui.ats

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionInfoParser
import com.v2ray.ang.ui.AboutActivity
import com.v2ray.ang.ui.base.HelperBaseComponentActivity
import com.v2ray.ang.ui.main.MainAction
import com.v2ray.ang.ui.main.MainActivity
import com.v2ray.ang.ui.main.MainRepository
import com.v2ray.ang.ui.main.MainViewModel
import com.v2ray.ang.ui.perappproxy.PerAppProxyActivity
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ATS: simplified start screen (big connect button, subscription, short settings).
 * Server data and the VPN service are driven by the upstream [MainViewModel]; the full
 * v2rayNG interface stays reachable as "Advanced settings" ([MainActivity]).
 */
class AtsHomeActivity : HelperBaseComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels {
        MainViewModel.Factory(application, MainRepository(application as AngApplication))
    }

    private val settingsViewModel: AtsSettingsViewModel by viewModels()

    private val requestVpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) startVpn()
        }

    private var resumedOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mainViewModel.onAction(MainAction.Initialize)
        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {}
    }

    /**
     * Refreshes the subscription (key) in the background when it is older than [maxAgeMs],
     * so "My subscription" shows the current end date, e.g. right after a renewal in the bot.
     */
    private fun refreshKeyIfStale(maxAgeMs: Long, showResult: Boolean = false) {
        lifecycleScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val stale = MmkvManager.decodeSubscriptions().any {
                it.subscription.enabled && it.subscription.url.isNotEmpty() &&
                        now - it.subscription.lastUpdated >= maxAgeMs
            }
            if (!stale) return@launch
            val result = try {
                AngConfigManager.updateConfigViaSubAll()
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "ATS: key refresh failed", e)
                null
            }
            withContext(Dispatchers.Main) {
                mainViewModel.onAction(MainAction.RefreshGroups)
                mainViewModel.refreshSelectedGuid()
                if (showResult) {
                    if (result != null && result.successCount > 0) toastSuccess(R.string.ats_key_refreshed)
                    else toastError(R.string.ats_key_refresh_failed)
                }
            }
        }
    }

    /** Coming back from the full v2rayNG screens or the bot: pick up anything changed there. */
    override fun onResume() {
        super.onResume()
        refreshKeyIfStale(KEY_REFRESH_INTERVAL_MS)
        if (!resumedOnce) {
            resumedOnce = true
            return
        }
        val restartService = SettingsChangeManager.consumeRestartService()
        SettingsChangeManager.consumeSetupGroupTab()
        mainViewModel.refreshUiSettings()
        mainViewModel.onAction(MainAction.RefreshGroups)
        settingsViewModel.reload()
        if (restartService) LauncherManager.restartService(this)
    }

    private fun openScreen(target: Class<*>) {
        startActivity(Intent(this, target))
    }

    @Composable
    override fun ScreenContent() {
        BackHandler { moveTaskToBack(false) }

        val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()
        val settings by settingsViewModel.state.collectAsStateWithLifecycle()

        // ATS works with the key's own group (the one the subscription fills), never the empty
        // "Default" group that v2rayNG keeps for manually added servers.
        val group = uiState.groups.firstOrNull { it.usageInfo != null }
            ?: uiState.groups.firstOrNull { it.id != AppConfig.DEFAULT_SUBSCRIPTION_ID }
            ?: uiState.groups.firstOrNull { it.id == uiState.selectedGroupId }
        val groupId = group?.id ?: uiState.selectedGroupId
        val servers by mainViewModel.serversForGroup(groupId).collectAsStateWithLifecycle()

        var tab by rememberSaveable { mutableStateOf(AtsTab.Home) }
        var showServerPicker by rememberSaveable { mutableStateOf(false) }

        LaunchedEffect(groupId) {
            if (groupId.isNotEmpty() && groupId != uiState.selectedGroupId) {
                mainViewModel.onAction(MainAction.SelectGroup(groupId))
            }
        }

        // No server selected (fresh key) or the selected one disappeared after a key refresh:
        // pick the first server of the key.
        LaunchedEffect(uiState.selectedGuid, servers) {
            val selected = uiState.selectedGuid
            if (servers.isNotEmpty() && (selected.isNullOrEmpty() || servers.none { it.guid == selected })) {
                mainViewModel.updateSelectedGuid(servers.first().guid)
                if (uiState.isRunning) LauncherManager.restartService(this@AtsHomeActivity)
            }
        }

        val usageInfo = group?.usageInfo

        val state = AtsScreenState(
            hasServers = !uiState.selectedGuid.isNullOrEmpty() || servers.isNotEmpty(),
            isRunning = uiState.isRunning,
            selectedGuid = uiState.selectedGuid,
            servers = servers,
            usageInfo = usageInfo,
            subscriptionName = group?.remarks?.takeUnless { it.isBlank() || it == "import sub" || it == "Default" },
            startOnBoot = settings.startOnBoot,
            versionName = BuildConfig.VERSION_NAME,
            adminUnlocked = settings.adminUnlocked,
            adminPinConfigured = settings.adminPinConfigured,
            pinStatus = settings.pinStatus,
        )

        AtsApp(
            state = state,
            tab = tab,
            onTabChange = { tab = it },
            showServerPicker = showServerPicker,
            onShowServerPicker = { showServerPicker = it },
            callbacks = AtsCallbacks(
                onToggleVpn = ::toggleVpn,
                onSelectServer = ::selectServer,
                onScanQr = ::importQrCode,
                onPasteKey = ::importClipboard,
                onRefreshKey = { refreshKeyIfStale(0L, showResult = true) },
                // Renewal and support always go to the ATS bot in Telegram, never to a web page.
                onRenew = { openLink(AppConfig.RENEW_URL) },
                onSupport = { openLink(AppConfig.SUPPORT_URL) },
                onStartOnBootChange = settingsViewModel::setStartOnBoot,
                onAppsWithoutVpn = {
                    startActivity(
                        Intent(this, PerAppProxyActivity::class.java)
                            .putExtra(PerAppProxyActivity.EXTRA_ATS_SIMPLE, true)
                    )
                },
                onAdvanced = { openScreen(MainActivity::class.java) },
                onAbout = { openScreen(AboutActivity::class.java) },
                onSubmitPin = settingsViewModel::submitAdminPin,
                onPinDialogClosed = settingsViewModel::resetPinStatus,
                onAdminUnavailable = { toast(R.string.ats_admin_unavailable) },
            ),
        )
    }

    private fun toggleVpn() {
        if (mainViewModel.uiState.value.isRunning) {
            LauncherManager.stopService(this)
            return
        }
        if (!SettingsManager.isVpnMode()) {
            startVpn()
            return
        }
        val intent = VpnService.prepare(this)
        if (intent == null) startVpn() else requestVpnPermission.launch(intent)
    }

    private fun startVpn() {
        if (mainViewModel.uiState.value.selectedGuid.isNullOrEmpty()) {
            toast(R.string.ats_no_server)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            checkAndRequestPermission(PermissionType.ACCESS_LOCAL_NETWORK) {}
        }
        LauncherManager.startService(this)
    }

    private fun selectServer(guid: String) {
        if (guid != mainViewModel.uiState.value.selectedGuid) {
            mainViewModel.updateSelectedGuid(guid)
            LauncherManager.restartService(this)
        }
    }

    private fun importQrCode() {
        launchQRCodeScanner { scanResult ->
            if (scanResult != null) importKey(scanResult)
        }
    }

    private fun importClipboard() {
        val text = try {
            Utils.getClipboard(this)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "ATS: failed to read the clipboard", e)
            ""
        }
        if (text.isBlank()) {
            toastError(R.string.ats_clipboard_empty)
            return
        }
        importKey(text)
    }

    /**
     * Adds a key. A key that is already in the app is not an error: it is refreshed instead,
     * which also picks up a renewal made in the bot.
     */
    private fun importKey(raw: String) {
        val text = raw.trim()
        lifecycleScope.launch(Dispatchers.IO) {
            val known = MmkvManager.decodeSubscriptions().any { it.subscription.url.trim() == text }
            withContext(Dispatchers.Main) {
                if (known) {
                    toast(R.string.ats_key_already_added)
                    refreshKeyIfStale(0L, showResult = true)
                } else {
                    mainViewModel.onAction(MainAction.ImportBatchConfig(text))
                }
            }
        }
    }

    companion object {
        private const val KEY_REFRESH_INTERVAL_MS = 30 * 60 * 1000L
    }

    private fun openLink(url: String) {
        // Links may come from the subscription panel, so only http(s) and tg:// are opened.
        val safeUrl = SubscriptionInfoParser.sanitizeLink(url) ?: return
        Utils.openUri(this, safeUrl)
    }
}
