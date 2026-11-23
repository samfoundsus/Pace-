package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.models.ConnectionType
import com.example.engine.speedtest.WifiBand

@Composable
fun ConnectionBadge(
    connectionType: ConnectionType,
    wifiBand: WifiBand? = null,
    wifiBandLabel: String? = null,
    cellularNetworkTag: String? = null,
    modifier: Modifier = Modifier,
) {
    val resolvedWifiBandText = wifiBandLabel?.trim()?.takeIf { it.isNotBlank() }
        ?: when (wifiBand) {
            WifiBand.BAND_2_4_GHZ -> stringResource(R.string.network_info_wifi_band_24)
            WifiBand.BAND_5_GHZ -> stringResource(R.string.network_info_wifi_band_5)
            WifiBand.BAND_6_GHZ -> stringResource(R.string.network_info_wifi_band_6)
            null -> null
        }
    val resolvedCellularTag = cellularNetworkTag?.trim()?.takeIf { it.isNotBlank() }

    val label = when (connectionType) {
        ConnectionType.WIFI -> {
            val base = stringResource(R.string.connection_wifi)
            if (resolvedWifiBandText != null) "$base · $resolvedWifiBandText" else base
        }
        ConnectionType.MOBILE -> {
            val base = stringResource(R.string.connection_mobile)
            if (resolvedCellularTag != null) "$base · $resolvedCellularTag" else base
        }
        ConnectionType.ETHERNET -> stringResource(R.string.connection_ethernet)
        ConnectionType.NONE -> stringResource(R.string.connection_none)
    }

    val icon = when (connectionType) {
        ConnectionType.WIFI -> SpeedTestIcons.Wifi
        ConnectionType.MOBILE -> SpeedTestIcons.MobileData
        ConnectionType.ETHERNET -> SpeedTestIcons.Ethernet
        ConnectionType.NONE -> SpeedTestIcons.Offline
    }

    val containerColor = when (connectionType) {
        ConnectionType.NONE -> MaterialTheme.colorScheme.errorContainer
        ConnectionType.MOBILE -> MaterialTheme.colorScheme.secondaryContainer
        ConnectionType.WIFI, ConnectionType.ETHERNET -> MaterialTheme.colorScheme.surfaceContainerHigh
    }

    val contentColor = when (connectionType) {
        ConnectionType.NONE -> MaterialTheme.colorScheme.onErrorContainer
        ConnectionType.MOBILE -> MaterialTheme.colorScheme.onSecondaryContainer
        ConnectionType.WIFI, ConnectionType.ETHERNET -> MaterialTheme.colorScheme.onSurface
    }

    Surface(
        modifier = modifier.testTag("connection_badge"),
        shape = RoundedCornerShape(50),
        color = containerColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = stringResource(R.string.cd_connection_icon),
                tint = contentColor,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
