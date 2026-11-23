package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.data.models.ConnectionType
import com.example.engine.speedtest.WifiBand
import com.example.engine.speedtest.WifiSignalQuality
import com.example.ui.components.WifiNameAccessIssue
import com.example.ui.components.openAppDetailsSettings
import com.example.ui.components.openLocationSettings
import com.example.ui.components.rememberPhoneStatePermissionLauncher
import com.example.ui.components.rememberWifiLocationPermissionLauncher
import com.example.ui.components.resolveWifiNameAccessIssue
import com.example.ui.theme.JetBrainsMono
import com.example.viewmodel.NetworkInfoUiState

private val InfoOuterCorner = 28.dp
private val InfoInnerCorner = 4.dp
private val InfoTileGap = 2.dp

private fun infoTileShape(index: Int, count: Int): Shape = when {
    count <= 1 -> RoundedCornerShape(InfoOuterCorner)
    index == 0 -> RoundedCornerShape(
        topStart = InfoOuterCorner,
        topEnd = InfoOuterCorner,
        bottomStart = InfoInnerCorner,
        bottomEnd = InfoInnerCorner,
    )
    index == count - 1 -> RoundedCornerShape(
        topStart = InfoInnerCorner,
        topEnd = InfoInnerCorner,
        bottomStart = InfoOuterCorner,
        bottomEnd = InfoOuterCorner,
    )
    else -> RoundedCornerShape(InfoInnerCorner)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkInfoScreen(
    uiState: NetworkInfoUiState,
    onNavigateBack: () -> Unit,
    onRefresh: () -> Unit,
    onResumeRefresh: () -> Unit,
    onWifiPermissionResult: (granted: Boolean, permanentlyDenied: Boolean) -> Unit,
    onPhonePermissionResult: (granted: Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Never request permission automatically; only when the user taps "Show Wi-Fi name" or "Show network type".
    val requestWifiLocationPermission = rememberWifiLocationPermissionLauncher(
        onResult = onWifiPermissionResult,
    )
    val requestPhoneStatePermission = rememberPhoneStatePermissionLauncher(
        onResult = onPhonePermissionResult,
    )

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                onResumeRefresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val wifiAccessIssue = resolveWifiNameAccessIssue(
        hasPermission = uiState.hasWifiLocationPermission,
        isLocationEnabled = uiState.isLocationEnabled,
        permissionDenied = uiState.wifiPermissionDenied,
        permissionPermanentlyDenied = uiState.wifiPermissionPermanentlyDenied,
    )

    val handleShowWifiName: () -> Unit = {
        when {
            !uiState.hasWifiLocationPermission -> {
                if (uiState.wifiPermissionPermanentlyDenied) {
                    openAppDetailsSettings(context)
                } else {
                    requestWifiLocationPermission()
                }
            }
            !uiState.isLocationEnabled -> openLocationSettings(context)
            else -> onResumeRefresh()
        }
    }

    val handleOpenWifiSettings: () -> Unit = {
        when (wifiAccessIssue) {
            WifiNameAccessIssue.LOCATION_OFF -> openLocationSettings(context)
            WifiNameAccessIssue.PERMISSION_DENIED,
            WifiNameAccessIssue.PERMISSION_PERMANENTLY_DENIED,
            -> openAppDetailsSettings(context)
            WifiNameAccessIssue.NONE -> Unit
        }
    }

    val showNetworkTypePrompt = !uiState.hasPhoneStatePermission && !uiState.phonePermissionDenied
    val handleShowNetworkType: () -> Unit = {
        requestPhoneStatePermission()
    }

    val unavailable = stringResource(R.string.value_unavailable)
    val checking = stringResource(R.string.network_info_checking)

    val wifiBandText = when (uiState.wifiBand) {
        WifiBand.BAND_2_4_GHZ -> stringResource(R.string.network_info_wifi_band_24)
        WifiBand.BAND_5_GHZ -> stringResource(R.string.network_info_wifi_band_5)
        WifiBand.BAND_6_GHZ -> stringResource(R.string.network_info_wifi_band_6)
        null -> null
    }
    val cellularTagText = uiState.cellularNetworkTag?.trim()?.takeIf { it.isNotBlank() }

    val connectionTypeLabel = when (uiState.connectionType) {
        ConnectionType.WIFI -> {
            val base = stringResource(R.string.connection_wifi)
            if (wifiBandText != null) "$base · $wifiBandText" else base
        }
        ConnectionType.MOBILE -> {
            val base = stringResource(R.string.connection_mobile)
            if (cellularTagText != null) "$base · $cellularTagText" else base
        }
        ConnectionType.ETHERNET -> stringResource(R.string.connection_ethernet)
        ConnectionType.NONE -> stringResource(R.string.connection_none)
    }

    val validatedLabel = when (uiState.connectionType) {
        ConnectionType.NONE -> unavailable
        else -> if (uiState.isValidated) {
            stringResource(R.string.network_info_validated_yes)
        } else {
            stringResource(R.string.network_info_validated_no)
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("network_info_screen"),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.network_info_title),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("network_info_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.cd_navigate_back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onRefresh,
                        modifier = Modifier.testTag("network_info_refresh_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.cd_refresh_network_info),
                        )
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 600.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                // Connection section
                item(key = "section_connection") {
                    InfoSection(title = stringResource(R.string.network_info_section_connection)) {
                        InfoValueTile(
                            label = stringResource(R.string.network_info_connection_type),
                            value = connectionTypeLabel,
                            shape = infoTileShape(index = 0, count = 2),
                            testTag = "network_info_type_row",
                        )
                        InfoValueTile(
                            label = stringResource(R.string.network_info_connection_validated),
                            value = validatedLabel,
                            shape = infoTileShape(index = 1, count = 2),
                            testTag = "network_info_validated_row",
                        )
                    }
                }

                // Wi-Fi section (when on Wi-Fi)
                if (uiState.connectionType == ConnectionType.WIFI) {
                    item(key = "section_wifi") {
                        val bandLabel = wifiBandText ?: unavailable

                        val linkSpeedLabel = uiState.wifiLinkSpeedMbps?.takeIf { it > 0 }?.let { speed ->
                            stringResource(R.string.network_info_link_speed_format, speed)
                        } ?: unavailable

                        val signalLabel = if (uiState.wifiRssiDbm != null && uiState.wifiSignalQuality != null) {
                            val qualityText = when (uiState.wifiSignalQuality) {
                                WifiSignalQuality.EXCELLENT -> stringResource(R.string.network_info_signal_excellent)
                                WifiSignalQuality.GOOD -> stringResource(R.string.network_info_signal_good)
                                WifiSignalQuality.FAIR -> stringResource(R.string.network_info_signal_fair)
                                WifiSignalQuality.WEAK -> stringResource(R.string.network_info_signal_weak)
                            }
                            stringResource(R.string.network_info_signal_format, uiState.wifiRssiDbm, qualityText)
                        } else {
                            unavailable
                        }

                        InfoSection(title = stringResource(R.string.network_info_section_wifi)) {
                            WifiSsidTile(
                                ssid = uiState.networkName,
                                hasPermission = uiState.hasWifiLocationPermission,
                                isLocationEnabled = uiState.isLocationEnabled,
                                accessIssue = wifiAccessIssue,
                                onShowWifiNameClick = handleShowWifiName,
                                onOpenSettingsClick = handleOpenWifiSettings,
                                shape = infoTileShape(index = 0, count = 4),
                            )
                            InfoValueTile(
                                label = stringResource(R.string.network_info_wifi_band),
                                value = bandLabel,
                                shape = infoTileShape(index = 1, count = 4),
                                monospaceValue = uiState.wifiBand != null,
                                testTag = "network_info_wifi_band_row",
                            )
                            InfoValueTile(
                                label = stringResource(R.string.network_info_wifi_link_speed),
                                value = linkSpeedLabel,
                                shape = infoTileShape(index = 2, count = 4),
                                monospaceValue = uiState.wifiLinkSpeedMbps != null,
                                testTag = "network_info_wifi_link_speed_row",
                            )
                            InfoValueTile(
                                label = stringResource(R.string.network_info_wifi_signal),
                                value = signalLabel,
                                shape = infoTileShape(index = 3, count = 4),
                                monospaceValue = uiState.wifiRssiDbm != null,
                                testTag = "network_info_wifi_signal_row",
                            )
                        }
                    }
                }

                // Mobile data section (when on mobile; shows carrier, generation tag or "Show network type" until allowed; if denied, carrier only with no tag and no error)
                if (uiState.connectionType == ConnectionType.MOBILE) {
                    val carrier = uiState.networkName?.trim()?.takeIf { it.isNotBlank() }
                    val showNetworkTypeRow = cellularTagText != null || showNetworkTypePrompt
                    val totalMobileRows = (if (carrier != null) 1 else 0) + (if (showNetworkTypeRow) 1 else 0)

                    if (totalMobileRows > 0) {
                        item(key = "section_mobile") {
                            InfoSection(title = stringResource(R.string.network_info_section_mobile)) {
                                var currentIndex = 0
                                if (carrier != null) {
                                    InfoValueTile(
                                        label = stringResource(R.string.network_info_mobile_carrier),
                                        value = carrier,
                                        shape = infoTileShape(index = currentIndex, count = totalMobileRows),
                                        testTag = "network_info_mobile_carrier_row",
                                    )
                                    currentIndex++
                                }
                                if (cellularTagText != null) {
                                    InfoValueTile(
                                        label = stringResource(R.string.network_info_mobile_network_type),
                                        value = cellularTagText,
                                        shape = infoTileShape(index = currentIndex, count = totalMobileRows),
                                        testTag = "network_info_mobile_tag_row",
                                    )
                                } else if (showNetworkTypePrompt) {
                                    MobileNetworkTypePromptTile(
                                        onShowNetworkTypeClick = handleShowNetworkType,
                                        shape = infoTileShape(index = currentIndex, count = totalMobileRows),
                                    )
                                }
                            }
                        }
                    }
                }

                // Internet section
                item(key = "section_internet") {
                    val showChecking = uiState.isLoadingInternetInfo && !uiState.hasLoadedInternetInfo
                    val publicIpText = when {
                        showChecking -> checking
                        else -> uiState.publicIp ?: unavailable
                    }
                    val ispText = when {
                        showChecking -> checking
                        else -> uiState.isp ?: unavailable
                    }
                    val ipv4Text = when {
                        showChecking -> checking
                        uiState.ipv4Supported == true -> stringResource(R.string.network_info_protocol_supported)
                        uiState.ipv4Supported == false -> stringResource(R.string.network_info_protocol_not_detected)
                        else -> unavailable
                    }
                    val ipv6Text = when {
                        showChecking -> checking
                        uiState.ipv6Supported == true -> stringResource(R.string.network_info_protocol_supported)
                        uiState.ipv6Supported == false -> stringResource(R.string.network_info_protocol_not_detected)
                        else -> unavailable
                    }

                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.network_info_section_internet),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            if (uiState.isLoadingInternetInfo) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .testTag("network_info_internet_loading"),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(InfoTileGap),
                        ) {
                            InfoValueTile(
                                label = stringResource(R.string.network_info_public_ip),
                                value = publicIpText,
                                shape = infoTileShape(index = 0, count = 4),
                                monospaceValue = uiState.publicIp != null && !showChecking,
                                testTag = "network_info_public_ip_row",
                            )
                            InfoValueTile(
                                label = stringResource(R.string.network_info_isp),
                                value = ispText,
                                shape = infoTileShape(index = 1, count = 4),
                                testTag = "network_info_isp_row",
                            )
                            InfoValueTile(
                                label = stringResource(R.string.network_info_ipv4),
                                value = ipv4Text,
                                shape = infoTileShape(index = 2, count = 4),
                                testTag = "network_info_ipv4_row",
                            )
                            InfoValueTile(
                                label = stringResource(R.string.network_info_ipv6),
                                value = ipv6Text,
                                shape = infoTileShape(index = 3, count = 4),
                                testTag = "network_info_ipv6_row",
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = stringResource(R.string.network_info_external_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(horizontal = 20.dp)
                                .testTag("network_info_external_note"),
                        )
                    }
                }

                item(key = "bottom_spacer") {
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun InfoSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 20.dp, bottom = 10.dp),
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(InfoTileGap),
        ) {
            content()
        }
    }
}

@Composable
private fun InfoValueTile(
    label: String,
    value: String,
    shape: Shape,
    monospaceValue: Boolean = false,
    testTag: String,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 58.dp)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(end = 16.dp),
            )
            Text(
                text = value,
                style = if (monospaceValue) {
                    MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = JetBrainsMono,
                        fontWeight = FontWeight.Medium,
                    )
                } else {
                    MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                    )
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

@Composable
private fun MobileNetworkTypePromptTile(
    onShowNetworkTypeClick: () -> Unit,
    shape: Shape,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("network_info_mobile_tag_row"),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 58.dp)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.network_info_mobile_network_type),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(end = 16.dp),
            )
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(
                        role = Role.Button,
                        onClick = onShowNetworkTypeClick,
                    )
                    .testTag("network_info_show_network_type_button"),
            ) {
                Text(
                    text = stringResource(R.string.action_show_network_type),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun WifiSsidTile(
    ssid: String?,
    hasPermission: Boolean,
    isLocationEnabled: Boolean,
    accessIssue: WifiNameAccessIssue,
    onShowWifiNameClick: () -> Unit,
    onOpenSettingsClick: () -> Unit,
    shape: Shape,
) {
    val canReadSsid = hasPermission && isLocationEnabled
    val sanitizedSsid = ssid?.trim()?.takeIf { it.isNotBlank() }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("network_info_wifi_ssid_row"),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.network_info_wifi_name),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(end = 16.dp),
                )

                when {
                    canReadSsid -> {
                        Text(
                            text = sanitizedSsid ?: stringResource(R.string.value_unavailable),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    accessIssue == WifiNameAccessIssue.NONE -> {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable(
                                    role = Role.Button,
                                    onClick = onShowWifiNameClick,
                                )
                                .testTag("network_info_show_wifi_name_button"),
                        ) {
                            Text(
                                text = stringResource(R.string.action_show_wifi_name),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                    else -> {
                        Text(
                            text = stringResource(R.string.value_unavailable),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (!canReadSsid && accessIssue != WifiNameAccessIssue.NONE) {
                val explanation = when (accessIssue) {
                    WifiNameAccessIssue.LOCATION_OFF ->
                        stringResource(R.string.wifi_location_off_note)
                    WifiNameAccessIssue.PERMISSION_PERMANENTLY_DENIED ->
                        stringResource(R.string.wifi_permission_permanently_denied_note)
                    WifiNameAccessIssue.PERMISSION_DENIED ->
                        stringResource(R.string.wifi_permission_denied_note)
                    WifiNameAccessIssue.NONE -> ""
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = explanation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable(
                                role = Role.Button,
                                onClick = onOpenSettingsClick,
                            )
                            .testTag("network_info_open_settings_button"),
                    ) {
                        Text(
                            text = stringResource(R.string.action_open_settings),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}
