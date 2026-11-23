package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.models.ConnectionType
import com.example.engine.speedtest.WifiBand

/**
 * Single-line connection & device info bar shown at the bottom of the Test screen and History detail screen.
 * - On Wi-Fi: shows Wi-Fi name (or "Show Wi-Fi name" tap action / "Wi-Fi name hidden"), then the Wi-Fi band.
 * - On Mobile data: shows active data SIM carrier, then the mobile generation (e.g. "Jio · 5G").
 *   On Android 11+ before READ_PHONE_STATE is granted, shows a "Show network type" tap action.
 *   If denied, shows the carrier name only, with no tag and no error.
 * - On both: shows device model (Build.MODEL), keeping the line on one line with ellipsis.
 */
@Composable
fun ConnectionInfoLine(
    connectionType: ConnectionType,
    networkName: String?,
    cellularNetworkTag: String?,
    deviceModel: String?,
    wifiBand: WifiBand? = null,
    wifiBandLabel: String? = null,
    wifiSsidHidden: Boolean = false,
    wifiAccessIssue: WifiNameAccessIssue = WifiNameAccessIssue.NONE,
    onShowWifiNameClick: (() -> Unit)? = null,
    onOpenWifiSettingsClick: (() -> Unit)? = null,
    showNetworkTypePrompt: Boolean = false,
    onShowNetworkTypeClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val icon = when (connectionType) {
        ConnectionType.WIFI -> SpeedTestIcons.Wifi
        ConnectionType.MOBILE -> SpeedTestIcons.MobileData
        ConnectionType.ETHERNET -> SpeedTestIcons.Ethernet
        ConnectionType.NONE -> SpeedTestIcons.Offline
    }

    val cd = stringResource(R.string.cd_connection_info_line)
    val sanitizedName = networkName?.trim()?.takeIf { it.isNotBlank() }
    val sanitizedModel = deviceModel?.trim()?.takeIf { it.isNotBlank() }
    val sanitizedTag = cellularNetworkTag?.trim()?.takeIf { it.isNotBlank() }
    val sanitizedWifiBand = wifiBandLabel?.trim()?.takeIf { it.isNotBlank() }
        ?: when (wifiBand) {
            WifiBand.BAND_2_4_GHZ -> stringResource(R.string.network_info_wifi_band_24)
            WifiBand.BAND_5_GHZ -> stringResource(R.string.network_info_wifi_band_5)
            WifiBand.BAND_6_GHZ -> stringResource(R.string.network_info_wifi_band_6)
            null -> null
        }

    val showHiddenWifi = connectionType == ConnectionType.WIFI && (sanitizedName == null || wifiSsidHidden)
    val showIssueDetail = showHiddenWifi &&
        wifiAccessIssue != WifiNameAccessIssue.NONE &&
        onOpenWifiSettingsClick != null

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("connection_info_line")
            .semantics { contentDescription = cd },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )

                var hasLeadingSegment = false

                when (connectionType) {
                    ConnectionType.WIFI -> {
                        if (!showHiddenWifi && sanitizedName != null) {
                            val wifiText = if (sanitizedWifiBand != null) {
                                "$sanitizedName · $sanitizedWifiBand"
                            } else {
                                sanitizedName
                            }
                            Text(
                                text = wifiText,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .testTag("connection_info_network_name"),
                            )
                            hasLeadingSegment = true
                        } else if (onShowWifiNameClick != null && wifiAccessIssue == WifiNameAccessIssue.NONE) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable(
                                        role = Role.Button,
                                        onClick = onShowWifiNameClick,
                                    )
                                    .testTag("show_wifi_name_button"),
                            ) {
                                Text(
                                    text = stringResource(R.string.action_show_wifi_name),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                            if (sanitizedWifiBand != null) {
                                Text(
                                    text = "·",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 1,
                                )
                                Text(
                                    text = sanitizedWifiBand,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.testTag("connection_info_wifi_band"),
                                )
                            }
                            hasLeadingSegment = true
                        } else {
                            val hiddenText = if (sanitizedWifiBand != null) {
                                "${stringResource(R.string.wifi_name_hidden)} · $sanitizedWifiBand"
                            } else {
                                stringResource(R.string.wifi_name_hidden)
                            }
                            Text(
                                text = hiddenText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .testTag("connection_info_wifi_hidden"),
                            )
                            hasLeadingSegment = true
                        }
                    }

                    ConnectionType.MOBILE -> {
                        val canShowNetworkTypeButton = sanitizedTag == null &&
                            showNetworkTypePrompt &&
                            onShowNetworkTypeClick != null

                        if (sanitizedTag != null) {
                            val mobileText = if (sanitizedName != null) {
                                "$sanitizedName · $sanitizedTag"
                            } else {
                                sanitizedTag
                            }
                            Text(
                                text = mobileText,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .testTag("connection_info_network_name"),
                            )
                            hasLeadingSegment = true
                        } else {
                            if (sanitizedName != null) {
                                Text(
                                    text = sanitizedName,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .weight(1f, fill = false)
                                        .testTag("connection_info_network_name"),
                                )
                                hasLeadingSegment = true
                            }
                            if (canShowNetworkTypeButton) {
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .clickable(
                                            role = Role.Button,
                                            onClick = onShowNetworkTypeClick,
                                        )
                                        .testTag("show_network_type_button"),
                                ) {
                                    Text(
                                        text = stringResource(R.string.action_show_network_type),
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    )
                                }
                                hasLeadingSegment = true
                            }
                        }
                    }

                    ConnectionType.ETHERNET -> {
                        Text(
                            text = stringResource(R.string.connection_ethernet),
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        hasLeadingSegment = true
                    }

                    ConnectionType.NONE -> {
                        Text(
                            text = stringResource(R.string.connection_none),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        hasLeadingSegment = true
                    }
                }

                if (sanitizedModel != null) {
                    if (hasLeadingSegment) {
                        Text(
                            text = "·",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                        )
                    }
                    Text(
                        text = sanitizedModel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(max = 150.dp)
                            .testTag("connection_info_device_model"),
                    )
                }
            }

            if (showIssueDetail) {
                val explanation = when (wifiAccessIssue) {
                    WifiNameAccessIssue.LOCATION_OFF ->
                        stringResource(R.string.wifi_location_off_note)
                    WifiNameAccessIssue.PERMISSION_PERMANENTLY_DENIED ->
                        stringResource(R.string.wifi_permission_permanently_denied_note)
                    WifiNameAccessIssue.PERMISSION_DENIED ->
                        stringResource(R.string.wifi_permission_denied_note)
                    WifiNameAccessIssue.NONE -> ""
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = explanation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable(
                                role = Role.Button,
                                onClick = { onOpenWifiSettingsClick?.invoke() },
                            )
                            .testTag("open_wifi_settings_button"),
                    ) {
                        Text(
                            text = stringResource(R.string.action_open_settings),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
            }
        }
    }
}
