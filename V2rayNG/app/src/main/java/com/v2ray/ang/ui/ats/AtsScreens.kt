package com.v2ray.ang.ui.ats

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ServersCache
import com.v2ray.ang.dto.entities.SubscriptionUsageInfo
import com.v2ray.ang.extension.toTrafficString
import java.text.DateFormat
import java.util.Date
import kotlin.math.ceil

// ATS palette (matches the design mock-up; the app runs in a dark theme by default).
private val AtsMint = Color(0xFF34D399)
private val AtsMintDark = Color(0xFF0E2A21)
private val AtsOnMint = Color(0xFF06281A)
private val AtsCard = Color(0xFF171D24)
private val AtsBorder = Color(0xFF232B34)
private val AtsMuted = Color(0xFF97A3B0)
private val AtsOff = Color(0xFF4A5563)
private val AtsBar = Color(0xFF12171D)

enum class AtsTab { Home, Subscription, Settings }

/** Everything the ATS screens need to draw, gathered by [AtsHomeActivity]. */
data class AtsScreenState(
    val hasServers: Boolean,
    val isRunning: Boolean,
    val selectedGuid: String?,
    val servers: List<ServersCache>,
    val usageInfo: SubscriptionUsageInfo?,
    val subscriptionName: String?,
    val startOnBoot: Boolean,
    val versionName: String,
    /** Admin entered the PIN in this session: "Advanced settings" is shown. */
    val adminUnlocked: Boolean,
    val adminPinConfigured: Boolean,
    val pinStatus: AtsPinStatus,
)

/** User operations the ATS screens can request; the activity routes each one. */
data class AtsCallbacks(
    val onToggleVpn: () -> Unit,
    val onSelectServer: (String) -> Unit,
    val onScanQr: () -> Unit,
    val onPasteKey: () -> Unit,
    val onRefreshKey: () -> Unit,
    val onRenew: () -> Unit,
    val onSupport: () -> Unit,
    val onStartOnBootChange: (Boolean) -> Unit,
    val onAppsWithoutVpn: () -> Unit,
    val onAdvanced: () -> Unit,
    val onAbout: () -> Unit,
    val onSubmitPin: (String) -> Unit,
    val onPinDialogClosed: () -> Unit,
    val onAdminUnavailable: () -> Unit,
)

@Composable
fun AtsApp(
    state: AtsScreenState,
    tab: AtsTab,
    onTabChange: (AtsTab) -> Unit,
    showServerPicker: Boolean,
    onShowServerPicker: (Boolean) -> Unit,
    callbacks: AtsCallbacks,
) {
    if (!state.hasServers) {
        AtsOnboarding(callbacks)
        return
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { AtsBottomBar(tab, onTabChange) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (tab) {
                AtsTab.Home -> AtsHome(state, callbacks, onOpenServers = { onShowServerPicker(true) })
                AtsTab.Subscription -> AtsSubscription(state, callbacks)
                AtsTab.Settings -> AtsSettings(state, callbacks, onOpenServers = { onShowServerPicker(true) })
            }
        }
    }
    if (showServerPicker) {
        AtsServerPicker(
            servers = state.servers,
            selectedGuid = state.selectedGuid,
            onSelect = { guid ->
                callbacks.onSelectServer(guid)
                onShowServerPicker(false)
            },
            onDismiss = { onShowServerPicker(false) },
        )
    }
}

// ---------------------------------------------------------------- first launch

@Composable
private fun AtsOnboarding(callbacks: AtsCallbacks) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(72.dp))
        Box(
            modifier = Modifier
                .size(109.dp)
                .background(AtsMintDark, RoundedCornerShape(32.dp))
                .border(2.dp, AtsMint, RoundedCornerShape(32.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_stat_name),
                contentDescription = null,
                tint = AtsMint,
                modifier = Modifier.size(60.dp),
            )
        }
        Spacer(Modifier.height(40.dp))
        Text(
            text = stringResource(R.string.app_name),
            fontSize = 64.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 10.sp,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.ats_onboarding_text),
            fontSize = 17.sp,
            color = AtsMuted,
            textAlign = TextAlign.Center,
            lineHeight = 25.sp,
        )
        Spacer(Modifier.height(64.dp))
        Text(
            text = stringResource(R.string.ats_onboarding_hint),
            fontSize = 14.sp,
            color = AtsMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        AtsPrimaryButton(
            text = stringResource(R.string.ats_scan_qr),
            iconRes = R.drawable.ic_qu_scan_24dp,
            onClick = callbacks.onScanQr,
        )
        Spacer(Modifier.height(12.dp))
        AtsSecondaryButton(
            text = stringResource(R.string.ats_paste_key),
            iconRes = R.drawable.ic_copy,
            onClick = callbacks.onPasteKey,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.ats_key_note),
            fontSize = 13.sp,
            color = AtsMuted,
            textAlign = TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------- home

@Composable
private fun AtsHome(state: AtsScreenState, callbacks: AtsCallbacks, onOpenServers: () -> Unit) {
    val selected = state.servers.firstOrNull { it.guid == state.selectedGuid }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 6.sp,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onBackground,
            )
            IconButton(onClick = callbacks.onSupport) {
                Icon(
                    painter = painterResource(R.drawable.ic_telegram_24dp),
                    contentDescription = stringResource(R.string.ats_support),
                    tint = AtsMuted,
                )
            }
        }

        Spacer(Modifier.height(40.dp))
        val ringColor = if (state.isRunning) AtsMint else AtsOff
        val actionLabel = stringResource(
            if (state.isRunning) R.string.ats_disconnect_action else R.string.ats_connect_action
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(200.dp)
                .background(if (state.isRunning) AtsMintDark else AtsCard, CircleShape)
                .border(6.dp, ringColor, CircleShape)
                .clickable(role = Role.Button, onClickLabel = actionLabel, onClick = callbacks.onToggleVpn)
                .semantics { contentDescription = actionLabel },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_ats_power),
                contentDescription = null,
                tint = ringColor,
                modifier = Modifier.size(64.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(if (state.isRunning) R.string.ats_connected else R.string.ats_disconnected),
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (state.isRunning) selected?.profile?.remarks.orEmpty()
            else stringResource(R.string.ats_tap_to_connect),
            fontSize = 15.sp,
            color = AtsMuted,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        Spacer(Modifier.height(40.dp))
        AtsCardRow(onClick = onOpenServers) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(AtsMintDark, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_ats_globe),
                    contentDescription = null,
                    tint = AtsMint,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 14.dp),
            ) {
                Text(
                    text = selected?.profile?.remarks ?: stringResource(R.string.ats_no_server),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                val delay = selected?.testDelayMillis ?: 0L
                Text(
                    text = if (delay > 0) stringResource(R.string.ats_server_ping, delay)
                    else stringResource(R.string.ats_server_label),
                    fontSize = 13.sp,
                    color = AtsMuted,
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_ats_chevron),
                contentDescription = null,
                tint = AtsMuted,
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AtsInfoTile(
                label = stringResource(R.string.ats_ru_sites),
                value = stringResource(R.string.ats_direct),
                valueColor = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            AtsInfoTile(
                label = stringResource(R.string.ats_other_sites),
                value = stringResource(R.string.ats_via_ats),
                valueColor = AtsMint,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------- subscription

@Composable
private fun AtsSubscription(state: AtsScreenState, callbacks: AtsCallbacks) {
    val info = state.usageInfo
    val now = System.currentTimeMillis()
    val expireMillis = info?.expire?.takeIf { it > 0L }?.times(1000L)
    val expired = expireMillis != null && expireMillis <= now
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        AtsScreenTitle(stringResource(R.string.ats_sub_title))
        Spacer(Modifier.height(16.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(AtsCard, RoundedCornerShape(20.dp))
                .border(1.dp, AtsBorder, RoundedCornerShape(20.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = info?.title ?: state.subscriptionName.orEmpty(),
                    fontSize = 14.sp,
                    color = AtsMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (info != null) {
                    Text(
                        text = stringResource(if (expired) R.string.ats_sub_expired else R.string.ats_sub_active),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (expired) Color.White else AtsOnMint,
                        modifier = Modifier
                            .background(
                                if (expired) MaterialTheme.colorScheme.error else AtsMint,
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            when {
                info == null -> Text(
                    text = stringResource(R.string.ats_sub_unknown),
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                )

                expireMillis == null -> Text(
                    text = stringResource(R.string.ats_sub_no_expiry),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )

                else -> {
                    val daysLeft = if (expired) 0 else ceil((expireMillis - now) / 86_400_000.0).toInt()
                    val dateText = DateFormat.getDateInstance(DateFormat.LONG).format(Date(expireMillis))
                    Text(
                        text = pluralStringResource(R.plurals.ats_days, daysLeft, daysLeft),
                        fontSize = 34.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = stringResource(R.string.ats_sub_until, dateText),
                        fontSize = 14.sp,
                        color = AtsMuted,
                    )
                    // Bar shows the share of a 30-day month that is left (full for longer plans).
                    LinearProgressIndicator(
                        progress = { (daysLeft / 30f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = AtsMint,
                        trackColor = AtsBorder,
                    )
                }
            }
            if (info != null) {
                Text(
                    text = if (info.total > 0) stringResource(
                        R.string.ats_sub_traffic,
                        info.used.toTrafficString(),
                        info.total.toTrafficString()
                    ) else stringResource(R.string.ats_sub_traffic_unlimited, info.used.toTrafficString()),
                    fontSize = 13.sp,
                    color = AtsMuted,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        AtsPrimaryButton(
            text = stringResource(R.string.ats_renew),
            iconRes = null,
            onClick = callbacks.onRenew,
        )
        Spacer(Modifier.height(12.dp))
        AtsSecondaryButton(
            text = stringResource(R.string.ats_refresh_key),
            iconRes = R.drawable.ic_refresh_24dp,
            onClick = callbacks.onRefreshKey,
        )
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------- settings

@Composable
private fun AtsSettings(state: AtsScreenState, callbacks: AtsCallbacks, onOpenServers: () -> Unit) {
    val selected = state.servers.firstOrNull { it.guid == state.selectedGuid }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        AtsScreenTitle(stringResource(R.string.ats_nav_settings))

        AtsSectionHeader(stringResource(R.string.ats_section_connection))
        AtsGroup {
            AtsSwitchRow(
                title = stringResource(R.string.ats_start_on_boot),
                subtitle = stringResource(R.string.ats_start_on_boot_sub),
                checked = state.startOnBoot,
                onCheckedChange = callbacks.onStartOnBootChange,
            )
            AtsDivider()
            AtsLinkRow(
                title = stringResource(R.string.ats_server_label),
                subtitle = selected?.profile?.remarks ?: stringResource(R.string.ats_no_server),
                onClick = onOpenServers,
            )
        }

        AtsSectionHeader(stringResource(R.string.ats_section_sites))
        AtsGroup {
            AtsLinkRow(
                title = stringResource(R.string.ats_apps_without_vpn),
                subtitle = stringResource(R.string.ats_apps_without_vpn_sub),
                onClick = callbacks.onAppsWithoutVpn,
            )
        }

        AtsSectionHeader(stringResource(R.string.ats_section_key))
        AtsGroup {
            AtsLinkRow(
                title = stringResource(R.string.ats_refresh_key),
                subtitle = state.subscriptionName,
                onClick = callbacks.onRefreshKey,
            )
            AtsDivider()
            AtsLinkRow(
                title = stringResource(R.string.ats_scan_qr),
                subtitle = null,
                onClick = callbacks.onScanQr,
            )
            AtsDivider()
            AtsLinkRow(
                title = stringResource(R.string.ats_paste_key),
                subtitle = null,
                onClick = callbacks.onPasteKey,
            )
        }

        AtsSectionHeader(stringResource(R.string.ats_section_help))
        AtsGroup {
            AtsLinkRow(
                title = stringResource(R.string.ats_write_support),
                subtitle = null,
                onClick = callbacks.onSupport,
            )
            // "Advanced settings" (full v2rayNG interface) is for the admin only.
            if (state.adminUnlocked) {
                AtsDivider()
                AtsLinkRow(
                    title = stringResource(R.string.ats_advanced),
                    subtitle = stringResource(R.string.ats_advanced_sub),
                    onClick = callbacks.onAdvanced,
                )
            }
            AtsDivider()
            AtsLinkRow(
                title = stringResource(R.string.ats_about),
                subtitle = null,
                onClick = callbacks.onAbout,
            )
        }

        // Seven taps on the version line open the admin PIN prompt.
        var versionTaps by remember { mutableIntStateOf(0) }
        var showPinDialog by rememberSaveable { mutableStateOf(false) }
        Text(
            text = stringResource(R.string.ats_version_line, state.versionName),
            fontSize = 13.sp,
            color = AtsMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    versionTaps++
                    if (versionTaps >= 7) {
                        versionTaps = 0
                        when {
                            state.adminUnlocked -> callbacks.onAdvanced()
                            state.adminPinConfigured -> showPinDialog = true
                            else -> callbacks.onAdminUnavailable()
                        }
                    }
                }
                .padding(top = 20.dp),
        )
        Spacer(Modifier.height(24.dp))

        if (showPinDialog && !state.adminUnlocked) {
            AtsPinDialog(
                status = state.pinStatus,
                onSubmit = callbacks.onSubmitPin,
                onDismiss = {
                    showPinDialog = false
                    callbacks.onPinDialogClosed()
                },
            )
        }
        // Correct PIN: close the prompt and open the advanced screen once.
        LaunchedEffect(state.adminUnlocked) {
            if (state.adminUnlocked && showPinDialog) {
                showPinDialog = false
                callbacks.onAdvanced()
            }
        }
    }
}

// ---------------------------------------------------------------- admin PIN

@Composable
private fun AtsPinDialog(
    status: AtsPinStatus,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pin by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ats_admin_title)) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .imePadding()
            ) {
                Text(
                    text = stringResource(R.string.ats_admin_text),
                    fontSize = 14.sp,
                    color = AtsMuted,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { value -> pin = value.filter { it.isDigit() }.take(12) },
                    label = { Text(stringResource(R.string.ats_admin_pin)) },
                    singleLine = true,
                    isError = status == AtsPinStatus.Wrong,
                    supportingText = if (status == AtsPinStatus.Wrong) {
                        { Text(stringResource(R.string.ats_admin_wrong)) }
                    } else null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { onSubmit(pin) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(pin) },
                enabled = pin.isNotEmpty() && status != AtsPinStatus.Checking,
            ) { Text(stringResource(R.string.ats_admin_open)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ats_cancel)) }
        },
    )
}

// ---------------------------------------------------------------- server picker

@Composable
private fun AtsServerPicker(
    servers: List<ServersCache>,
    selectedGuid: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ats_choose_server)) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .selectableGroup()
            ) {
                servers.forEach { server ->
                    val isSelected = server.guid == selectedGuid
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .selectable(
                                selected = isSelected,
                                role = Role.RadioButton,
                                onClick = { onSelect(server.guid) },
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = isSelected, onClick = null)
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp)
                        ) {
                            Text(
                                text = server.profile.remarks,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (server.testDelayMillis > 0) {
                                Text(
                                    text = stringResource(R.string.ats_server_ping, server.testDelayMillis),
                                    fontSize = 13.sp,
                                    color = AtsMuted,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ats_close)) }
        },
    )
}

// ---------------------------------------------------------------- building blocks

@Composable
private fun AtsBottomBar(tab: AtsTab, onTabChange: (AtsTab) -> Unit) {
    val colors = NavigationBarItemDefaults.colors(
        selectedIconColor = AtsMint,
        selectedTextColor = AtsMint,
        indicatorColor = AtsMintDark,
        unselectedIconColor = AtsMuted,
        unselectedTextColor = AtsMuted,
    )
    NavigationBar(containerColor = AtsBar) {
        NavigationBarItem(
            selected = tab == AtsTab.Home,
            onClick = { onTabChange(AtsTab.Home) },
            icon = { Icon(painterResource(R.drawable.ic_ats_power), contentDescription = null) },
            label = { Text(stringResource(R.string.ats_nav_home)) },
            colors = colors,
        )
        NavigationBarItem(
            selected = tab == AtsTab.Subscription,
            onClick = { onTabChange(AtsTab.Subscription) },
            icon = { Icon(painterResource(R.drawable.ic_ats_card), contentDescription = null) },
            label = { Text(stringResource(R.string.ats_nav_subscription)) },
            colors = colors,
        )
        NavigationBarItem(
            selected = tab == AtsTab.Settings,
            onClick = { onTabChange(AtsTab.Settings) },
            icon = { Icon(painterResource(R.drawable.ic_settings_24dp), contentDescription = null) },
            label = { Text(stringResource(R.string.ats_nav_settings)) },
            colors = colors,
        )
    }
}

@Composable
private fun AtsScreenTitle(text: String) {
    Text(
        text = text,
        fontSize = 28.sp,
        fontWeight = FontWeight.ExtraBold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun AtsSectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = AtsMuted,
        modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp),
    )
}

@Composable
private fun AtsGroup(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AtsCard, RoundedCornerShape(16.dp))
            .border(1.dp, AtsBorder, RoundedCornerShape(16.dp))
    ) {
        content()
    }
}

@Composable
private fun AtsDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(AtsBorder)
    )
}

@Composable
private fun AtsSwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AtsRowText(title, subtitle, Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = null,
        )
    }
}

@Composable
private fun AtsLinkRow(title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AtsRowText(title, subtitle, Modifier.weight(1f))
        Icon(
            painter = painterResource(R.drawable.ic_ats_chevron),
            contentDescription = null,
            tint = AtsMuted,
        )
    }
}

@Composable
private fun AtsRowText(title: String, subtitle: String?, modifier: Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (!subtitle.isNullOrEmpty()) {
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = AtsMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AtsCardRow(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .background(AtsCard, RoundedCornerShape(16.dp))
            .border(1.dp, AtsBorder, RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

@Composable
private fun AtsInfoTile(label: String, value: String, valueColor: Color, modifier: Modifier) {
    Column(
        modifier = modifier
            .background(AtsCard, RoundedCornerShape(16.dp))
            .border(1.dp, AtsBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = label, fontSize = 13.sp, color = AtsMuted)
        Text(text = value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = valueColor)
    }
}

@Composable
private fun AtsPrimaryButton(text: String, iconRes: Int?, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AtsMint, contentColor = AtsOnMint),
    ) {
        if (iconRes != null) {
            Icon(painterResource(iconRes), contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.size(10.dp))
        }
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AtsSecondaryButton(text: String, iconRes: Int?, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, AtsBorder),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = AtsCard,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ),
    ) {
        if (iconRes != null) {
            Icon(painterResource(iconRes), contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.size(10.dp))
        }
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}
