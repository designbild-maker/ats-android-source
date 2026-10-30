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
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
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

    /** Coming back from the full v2rayNG screens: pick up anything changed there. */
    override fun onResume() {
        super.onResume()
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
        val servers by mainViewModel.serversForGroup(uiState.selectedGroupId).collectAsStateWithLifecycle()

        var tab by rememberSaveable { mutableStateOf(AtsTab.Home) }
        var showServerPicker by rememberSaveable { mutableStateOf(false) }

        // A freshly imported key has servers but none selected yet: pick the first one.
        LaunchedEffect(uiState.selectedGuid, servers) {
            if (uiState.selectedGuid.isNullOrEmpty()) {
                servers.firstOrNull()?.let { mainViewModel.updateSelectedGuid(it.guid) }
            }
        }

        val group = uiState.groups.firstOrNull { it.id == uiState.selectedGroupId }
        val usageInfo = group?.usageInfo ?: uiState.groups.firstNotNullOfOrNull { it.usageInfo }

        val state = AtsScreenState(
            hasServers = !uiState.selectedGuid.isNullOrEmpty() || servers.isNotEmpty(),
            isRunning = uiState.isRunning,
            selectedGuid = uiState.selectedGuid,
            servers = servers,
            usageInfo = usageInfo,
            subscriptionName = group?.remarks,
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
                onRefreshKey = { mainViewModel.onAction(MainAction.UpdateSubscriptions) },
                onRenew = { openLink(usageInfo?.webPageUrl ?: usageInfo?.supportUrl ?: AppConfig.SUPPORT_URL) },
                onSupport = { openLink(usageInfo?.supportUrl ?: AppConfig.SUPPORT_URL) },
                onStartOnBootChange = settingsViewModel::setStartOnBoot,
                onAppsWithoutVpn = { openScreen(PerAppProxyActivity::class.java) },
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
            if (scanResult != null) {
                mainViewModel.onAction(MainAction.ImportBatchConfig(scanResult))
            }
        }
    }

    private fun importClipboard() {
        try {
            val text = Utils.getClipboard(this)
            mainViewModel.onAction(MainAction.ImportBatchConfig(text))
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "ATS: failed to import key from clipboard", e)
        }
    }

    private fun openLink(url: String) {
        // Links may come from the subscription panel, so only http(s) and tg:// are opened.
        val safeUrl = SubscriptionInfoParser.sanitizeLink(url) ?: return
        Utils.openUri(this, safeUrl)
    }
}
