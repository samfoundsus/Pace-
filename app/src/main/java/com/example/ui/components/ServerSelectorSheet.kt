package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.server.CustomServerResult
import com.example.data.server.ServerCandidateState
import com.example.data.server.ServerKind
import com.example.data.server.ServerProbeStatus
import com.example.data.server.ServerProfile
import com.example.data.server.ServerSelectorUiState
import com.example.ui.theme.JetBrainsMono

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSelectorSheet(
    selectorState: ServerSelectorUiState,
    onDismiss: () -> Unit,
    onSelectServer: (String) -> Unit,
    onRefresh: () -> Unit,
    onAddCustomServer: (
        name: String,
        baseUrl: String,
        downloadPath: String,
        uploadPath: String,
        pingPath: String,
        getIpPath: String,
        onResult: (CustomServerResult) -> Unit,
    ) -> Unit,
    onDeleteCustomServer: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showUnavailable by rememberSaveable { mutableStateOf(false) }
    var showAddDialog by rememberSaveable { mutableStateOf(false) }

    val unavailableCount = remember(selectorState.candidates) {
        selectorState.candidates.count { it.status == ServerProbeStatus.UNAVAILABLE }
    }
    val visibleCandidates = remember(selectorState.candidates, showUnavailable) {
        if (showUnavailable) {
            selectorState.candidates
        } else {
            selectorState.candidates.filter { it.status != ServerProbeStatus.UNAVAILABLE }
        }
    }

    if (showAddDialog) {
        AddCustomServerDialog(
            onDismiss = { showAddDialog = false },
            onValidateAndSave = { name, baseUrl, dlPath, ulPath, pingPath, getIpPath, onResult ->
                onAddCustomServer(name, baseUrl, dlPath, ulPath, pingPath, getIpPath) { result ->
                    onResult(result)
                    if (result is CustomServerResult.Success) {
                        showAddDialog = false
                    }
                }
            },
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = Modifier.testTag("server_selector_sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            // Sheet header with Refresh and Add actions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.server_sheet_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onRefresh,
                    enabled = !selectorState.isProbing,
                    modifier = Modifier.testTag("server_sheet_refresh_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.action_refresh),
                    )
                }
                IconButton(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.testTag("server_sheet_add_icon_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = stringResource(R.string.server_add_custom),
                    )
                }
            }

            if (selectorState.isProbing) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                        .testTag("server_probing_indicator"),
                )
                Text(
                    text = stringResource(R.string.server_probing_status),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                )
            }

            if (selectorState.communityLoadError) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp)
                        .testTag("server_community_error_banner"),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(R.string.server_community_load_error),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = 420.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item(key = "auto_fastest") {
                    AutoServerItem(
                        selected = selectorState.isAutoSelected,
                        fastestServer = selectorState.autoFastestServer,
                        fastestPingMs = selectorState.autoFastestPingMs,
                        onClick = {
                            onSelectServer(ServerProfile.AUTO_SERVER_ID)
                            onDismiss()
                        },
                    )
                }

                items(
                    items = visibleCandidates,
                    key = { it.profile.id },
                ) { candidate ->
                    ServerCandidateItem(
                        candidate = candidate,
                        selected = !selectorState.isAutoSelected &&
                            selectorState.selectedServerId == candidate.profile.id,
                        onClick = {
                            onSelectServer(candidate.profile.id)
                            onDismiss()
                        },
                        onDelete = if (candidate.profile.kind == ServerKind.CUSTOM) {
                            { onDeleteCustomServer(candidate.profile.id) }
                        } else {
                            null
                        },
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )

            // Show unavailable toggle (off by default)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        role = Role.Switch,
                        onClick = { showUnavailable = !showUnavailable },
                    )
                    .padding(horizontal = 20.dp, vertical = 6.dp)
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (unavailableCount > 0) {
                        stringResource(R.string.server_show_unavailable_count, unavailableCount)
                    } else {
                        stringResource(R.string.server_show_unavailable)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = showUnavailable,
                    onCheckedChange = { showUnavailable = it },
                    modifier = Modifier.testTag("show_unavailable_servers_switch"),
                )
            }

            // Add your own server button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
            ) {
                FilledTonalButton(
                    onClick = { showAddDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("add_custom_server_button"),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.server_add_custom),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun AutoServerItem(
    selected: Boolean,
    fastestServer: ServerProfile?,
    fastestPingMs: Double?,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val secondaryColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .testTag("server_option_auto"),
        shape = shape,
        color = containerColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 58.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.server_auto_fastest),
                    style = MaterialTheme.typography.titleSmall,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = if (fastestServer != null) {
                    fastestServer.displayNameWithLocation()
                } else {
                    stringResource(R.string.server_auto_subtitle)
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = secondaryColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (fastestPingMs != null) {
                Text(
                    text = stringResource(R.string.server_ping_ms, formatLatencyReadout(fastestPingMs)),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontFamily = JetBrainsMono,
                        fontFeatureSettings = "tnum",
                    ),
                    color = contentColor,
                    maxLines = 1,
                )
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = stringResource(R.string.cd_selected_server),
                    tint = contentColor,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun ServerCandidateItem(
    candidate: ServerCandidateState,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val profile = candidate.profile
    val isUnavailable = candidate.status == ServerProbeStatus.UNAVAILABLE
    val shape = RoundedCornerShape(16.dp)
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val secondaryColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(
                enabled = !isUnavailable,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .alpha(if (isUnavailable) 0.56f else 1f)
            .testTag("server_option_${profile.id}"),
        shape = shape,
        color = containerColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 58.dp)
                .padding(start = 16.dp, end = if (onDelete != null) 4.dp else 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    when (profile.kind) {
                        ServerKind.COMMUNITY -> ServerKindTag(
                            label = stringResource(R.string.server_tag_community),
                            selected = selected,
                        )
                        ServerKind.CUSTOM -> ServerKindTag(
                            label = stringResource(R.string.server_tag_custom),
                            selected = selected,
                        )
                        ServerKind.CLOUDFLARE -> Unit
                    }
                }
                if (profile.location.isNotBlank()) {
                    Text(
                        text = profile.location,
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            when (candidate.status) {
                ServerProbeStatus.HEALTHY -> {
                    candidate.medianPingMs?.let { ping ->
                        Text(
                            text = stringResource(R.string.server_ping_ms, formatLatencyReadout(ping)),
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontFamily = JetBrainsMono,
                                fontFeatureSettings = "tnum",
                            ),
                            color = contentColor,
                            maxLines = 1,
                        )
                    }
                }
                ServerProbeStatus.PROBING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                }
                ServerProbeStatus.UNAVAILABLE -> {
                    Text(
                        text = stringResource(R.string.value_unavailable),
                        style = MaterialTheme.typography.labelSmall,
                        color = secondaryColor,
                        maxLines = 1,
                    )
                }
                ServerProbeStatus.PENDING -> Unit
            }

            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = stringResource(R.string.cd_selected_server),
                    tint = contentColor,
                    modifier = Modifier.size(20.dp),
                )
            }

            if (onDelete != null) {
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("delete_custom_server_${profile.id}"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.cd_delete_server),
                        tint = secondaryColor,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerKindTag(
    label: String,
    selected: Boolean,
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun AddCustomServerDialog(
    onDismiss: () -> Unit,
    onValidateAndSave: (
        name: String,
        baseUrl: String,
        downloadPath: String,
        uploadPath: String,
        pingPath: String,
        getIpPath: String,
        onResult: (CustomServerResult) -> Unit,
    ) -> Unit,
) {
    var baseUrl by rememberSaveable { mutableStateOf("") }
    var serverName by rememberSaveable { mutableStateOf("") }
    var dlPath by rememberSaveable { mutableStateOf(ServerProfile.DEFAULT_LIBRESPEED_DL_PATH) }
    var ulPath by rememberSaveable { mutableStateOf(ServerProfile.DEFAULT_LIBRESPEED_UL_PATH) }
    var pingPath by rememberSaveable { mutableStateOf(ServerProfile.DEFAULT_LIBRESPEED_PING_PATH) }
    var getIpPath by rememberSaveable { mutableStateOf(ServerProfile.DEFAULT_LIBRESPEED_GET_IP_PATH) }
    var isValidating by remember { mutableStateOf(false) }
    var errorResId by remember { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = { if (!isValidating) onDismiss() },
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.testTag("add_custom_server_dialog"),
        title = {
            Text(
                text = stringResource(R.string.server_add_custom),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.server_add_custom_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = {
                        baseUrl = it
                        errorResId = null
                    },
                    label = { Text(stringResource(R.string.server_custom_base_url_label)) },
                    placeholder = { Text("https://speed.example.com") },
                    singleLine = true,
                    enabled = !isValidating,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("custom_server_base_url_input"),
                )
                OutlinedTextField(
                    value = serverName,
                    onValueChange = { serverName = it },
                    label = { Text(stringResource(R.string.server_custom_name_label)) },
                    singleLine = true,
                    enabled = !isValidating,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("custom_server_name_input"),
                )
                Text(
                    text = stringResource(R.string.server_custom_paths_heading),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                OutlinedTextField(
                    value = dlPath,
                    onValueChange = { dlPath = it },
                    label = { Text(stringResource(R.string.server_custom_dl_path_label)) },
                    singleLine = true,
                    enabled = !isValidating,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = ulPath,
                    onValueChange = { ulPath = it },
                    label = { Text(stringResource(R.string.server_custom_ul_path_label)) },
                    singleLine = true,
                    enabled = !isValidating,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = pingPath,
                    onValueChange = { pingPath = it },
                    label = { Text(stringResource(R.string.server_custom_ping_path_label)) },
                    singleLine = true,
                    enabled = !isValidating,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = getIpPath,
                    onValueChange = { getIpPath = it },
                    label = { Text(stringResource(R.string.server_custom_getip_path_label)) },
                    singleLine = true,
                    enabled = !isValidating,
                    modifier = Modifier.fillMaxWidth(),
                )

                errorResId?.let { errId ->
                    Text(
                        text = stringResource(errId),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("custom_server_error_text"),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isValidating) return@Button
                    isValidating = true
                    errorResId = null
                    onValidateAndSave(
                        serverName,
                        baseUrl,
                        dlPath,
                        ulPath,
                        pingPath,
                        getIpPath,
                    ) { result ->
                        isValidating = false
                        errorResId = when (result) {
                            is CustomServerResult.Success -> null
                            CustomServerResult.InvalidUrl -> R.string.server_custom_error_invalid_url
                            CustomServerResult.Unreachable -> R.string.server_custom_error_unreachable
                        }
                    }
                },
                enabled = !isValidating && baseUrl.isNotBlank(),
                modifier = Modifier.testTag("custom_server_save_button"),
            ) {
                if (isValidating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.server_custom_checking))
                } else {
                    Text(stringResource(R.string.action_save))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isValidating,
                modifier = Modifier.testTag("custom_server_cancel_button"),
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
