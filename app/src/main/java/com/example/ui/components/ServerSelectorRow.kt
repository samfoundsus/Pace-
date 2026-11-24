package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.server.ServerProfile
import com.example.data.server.ServerSelectorUiState

/**
 * Compact Pixel-style tonal tile (16dp corners) directly below the Start/Cancel button.
 * Shows "Server" label, active server value ("Auto · <name>" or "<name> · <location>"),
 * and a small chevron. Disabled while a test runs while still showing the active server.
 */
@Composable
fun ServerSelectorRow(
    selectorState: ServerSelectorUiState,
    activeTestServer: ServerProfile?,
    isRunning: Boolean,
    isResolvingServer: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val valueText = resolveServerRowValue(
        selectorState = selectorState,
        activeTestServer = activeTestServer,
        isRunning = isRunning,
        isResolvingServer = isResolvingServer,
    )
    val shape = RoundedCornerShape(16.dp)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(
                enabled = !isRunning,
                role = Role.Button,
                onClick = onClick,
            )
            .testTag("server_selector_row"),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = SpeedTestIcons.Server,
                contentDescription = stringResource(R.string.cd_server_icon),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.server_row_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .testTag("server_selector_value"),
            )
            Icon(
                imageVector = SpeedTestIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .alpha(if (isRunning) 0.35f else 1f),
            )
        }
    }
}

@Composable
private fun resolveServerRowValue(
    selectorState: ServerSelectorUiState,
    activeTestServer: ServerProfile?,
    isRunning: Boolean,
    isResolvingServer: Boolean,
): String {
    if (isResolvingServer) {
        return stringResource(R.string.server_resolving)
    }
    if (isRunning && activeTestServer != null) {
        return if (selectorState.isAutoSelected) {
            stringResource(R.string.server_auto_value, activeTestServer.name)
        } else {
            activeTestServer.displayNameWithLocation()
        }
    }
    if (selectorState.isAutoSelected) {
        val resolved = activeTestServer ?: selectorState.autoFastestServer
        return if (resolved != null) {
            stringResource(R.string.server_auto_value, resolved.name)
        } else {
            stringResource(R.string.server_auto_fastest)
        }
    }
    val manual = selectorState.selectedManualProfile ?: activeTestServer ?: ServerProfile.CLOUDFLARE
    return manual.displayNameWithLocation()
}
