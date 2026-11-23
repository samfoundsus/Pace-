package com.example.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.data.models.SpeedTestError
import com.example.data.models.TestResult
import com.example.data.models.TestStage
import com.example.data.settings.SpeedUnit
import com.example.engine.export.ResultCardRenderer
import com.example.ui.components.BufferbloatCard
import com.example.ui.components.ConnectionBadge
import com.example.ui.components.ConnectionInfoLine
import com.example.ui.components.EstimatedRatingsCard
import com.example.ui.components.KeepScreenOnEffect
import com.example.ui.components.LiveSpeedGraph
import com.example.ui.components.MobileDataWarningDialog
import com.example.ui.components.OoklaLatencyRow
import com.example.ui.components.SpeedGauge
import com.example.ui.components.SpeedTestIcons
import com.example.ui.components.ThroughputCardsRow
import com.example.ui.components.WifiNameAccessIssue
import com.example.ui.components.openAppDetailsSettings
import com.example.ui.components.openLocationSettings
import com.example.ui.components.rememberPhoneStatePermissionLauncher
import com.example.ui.components.rememberWifiLocationPermissionLauncher
import com.example.ui.components.resolveWifiNameAccessIssue
import com.example.viewmodel.SpeedTestUiState
import kotlinx.coroutines.launch

@Composable
fun TestScreen(
    uiState: SpeedTestUiState,
    speedUnit: SpeedUnit,
    onStartClick: () -> Unit,
    onCancelClick: () -> Unit,
    onToggleGaugeMetric: () -> Unit,
    onConfirmMobileData: () -> Unit,
    onDismissMobileData: () -> Unit,
    onDismissError: () -> Unit,
    onWifiPermissionResult: (granted: Boolean, permanentlyDenied: Boolean) -> Unit,
    onPhonePermissionResult: (granted: Boolean) -> Unit = {},
    onRefreshConnectionInfo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    var isSharingCard by remember { mutableStateOf(false) }

    // Keep screen awake strictly while a speed test is actively running.
    KeepScreenOnEffect(enabled = uiState.isRunning)

    // Never auto-request location or phone permission on launch or open; only invoke on explicit tap.
    val requestWifiLocationPermission = rememberWifiLocationPermissionLauncher(
        onResult = onWifiPermissionResult,
    )
    val requestPhoneStatePermission = rememberPhoneStatePermissionLauncher(
        onResult = onPhonePermissionResult,
    )

    // Refresh SSID / location / carrier / mobile network type whenever returning to the Test screen
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                onRefreshConnectionInfo()
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
            else -> onRefreshConnectionInfo()
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

    val shareChooserTitle = stringResource(R.string.share_chooser_title)
    val shareErrorText = stringResource(R.string.share_card_error)
    val handleShareResult: (TestResult) -> Unit = { result ->
        if (!isSharingCard) {
            isSharingCard = true
            coroutineScope.launch {
                val outcome = ResultCardRenderer.shareResultCard(
                    context = context,
                    result = result,
                    speedUnit = speedUnit,
                    chooserTitle = shareChooserTitle,
                )
                isSharingCard = false
                if (outcome.isFailure) {
                    Toast.makeText(context, shareErrorText, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    if (uiState.showMobileDataWarning) {
        MobileDataWarningDialog(
            onRunAnyway = onConfirmMobileData,
            onCancel = onDismissMobileData,
        )
    }

    val activeSamples = when (uiState.activeGraphStage) {
        TestStage.UPLOAD -> uiState.uploadSamples
        TestStage.DOWNLOAD -> uiState.downloadSamples
        else -> emptyList()
    }

    val completedResult = uiState.completedResult

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .testTag("test_screen"),
    ) {
        val isExpanded = maxWidth >= 720.dp

        if (isExpanded) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    TestHeaderRow(uiState = uiState)
                    SpeedGauge(
                        speedMbps = uiState.gaugeSpeedMbps,
                        stage = uiState.stage,
                        isWarmingUp = uiState.isWarmingUp,
                        stageProgress = uiState.stageProgress,
                        livePingMs = uiState.livePingMs,
                        speedUnit = speedUnit,
                        completedMetric = uiState.gaugeDisplayMetric,
                        onToggleMetric = onToggleGaugeMetric,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                    OoklaLatencyRow(
                        idlePingMs = uiState.displayedPingMs,
                        downloadPingMs = uiState.displayedDownloadLoadedLatencyMs,
                        uploadPingMs = uiState.displayedUploadLoadedLatencyMs,
                        jitterMs = uiState.displayedJitterMs,
                    )
                    TestActionRow(
                        isRunning = uiState.isRunning,
                        completedResult = completedResult,
                        isSharing = isSharingCard,
                        onStartClick = onStartClick,
                        onCancelClick = onCancelClick,
                        onShareClick = { completedResult?.let(handleShareResult) },
                        modifier = Modifier.widthIn(min = 220.dp, max = 380.dp),
                    )
                    ConnectionInfoLine(
                        connectionType = uiState.connectionType,
                        networkName = uiState.networkName,
                        cellularNetworkTag = uiState.cellularNetworkTag,
                        deviceModel = uiState.deviceModel,
                        wifiBand = uiState.wifiBand,
                        wifiSsidHidden = uiState.wifiSsidHidden,
                        wifiAccessIssue = wifiAccessIssue,
                        onShowWifiNameClick = handleShowWifiName,
                        onOpenWifiSettingsClick = handleOpenWifiSettings,
                        showNetworkTypePrompt = showNetworkTypePrompt,
                        onShowNetworkTypeClick = handleShowNetworkType,
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1.1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    ErrorBanner(
                        error = uiState.error,
                        onDismiss = onDismissError,
                    )
                    LiveSpeedGraph(
                        samples = activeSamples,
                        graphStage = uiState.activeGraphStage,
                        speedUnit = speedUnit,
                        phaseDurationSeconds = uiState.phaseDurationSeconds,
                    )
                    ThroughputCardsRow(
                        downloadMbps = uiState.displayedDownloadMbps,
                        uploadMbps = uiState.displayedUploadMbps,
                        speedUnit = speedUnit,
                    )
                    AnimatedVisibility(
                        visible = completedResult != null,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        if (completedResult != null) {
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                BufferbloatCard(
                                    idlePingMs = completedResult.ping,
                                    downloadLoadedLatencyMs = completedResult.downloadLoadedLatency,
                                    uploadLoadedLatencyMs = completedResult.uploadLoadedLatency,
                                    grade = completedResult.bufferbloatGrade,
                                )
                                EstimatedRatingsCard(result = completedResult)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                TestHeaderRow(
                    uiState = uiState,
                    modifier = Modifier.widthIn(max = 560.dp),
                )

                ErrorBanner(
                    error = uiState.error,
                    onDismiss = onDismissError,
                    modifier = Modifier.widthIn(max = 560.dp),
                )

                SpeedGauge(
                    speedMbps = uiState.gaugeSpeedMbps,
                    stage = uiState.stage,
                    isWarmingUp = uiState.isWarmingUp,
                    stageProgress = uiState.stageProgress,
                    livePingMs = uiState.livePingMs,
                    speedUnit = speedUnit,
                    completedMetric = uiState.gaugeDisplayMetric,
                    onToggleMetric = onToggleGaugeMetric,
                    modifier = Modifier.padding(vertical = 16.dp),
                )

                OoklaLatencyRow(
                    idlePingMs = uiState.displayedPingMs,
                    downloadPingMs = uiState.displayedDownloadLoadedLatencyMs,
                    uploadPingMs = uiState.displayedUploadLoadedLatencyMs,
                    jitterMs = uiState.displayedJitterMs,
                    modifier = Modifier.widthIn(max = 560.dp),
                )

                TestActionRow(
                    isRunning = uiState.isRunning,
                    completedResult = completedResult,
                    isSharing = isSharingCard,
                    onStartClick = onStartClick,
                    onCancelClick = onCancelClick,
                    onShareClick = { completedResult?.let(handleShareResult) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 560.dp),
                )

                LiveSpeedGraph(
                    samples = activeSamples,
                    graphStage = uiState.activeGraphStage,
                    speedUnit = speedUnit,
                    phaseDurationSeconds = uiState.phaseDurationSeconds,
                    modifier = Modifier.widthIn(max = 560.dp),
                )

                ThroughputCardsRow(
                    downloadMbps = uiState.displayedDownloadMbps,
                    uploadMbps = uiState.displayedUploadMbps,
                    speedUnit = speedUnit,
                    modifier = Modifier.widthIn(max = 560.dp),
                )

                AnimatedVisibility(
                    visible = completedResult != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                    modifier = Modifier.widthIn(max = 560.dp),
                ) {
                    if (completedResult != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            BufferbloatCard(
                                idlePingMs = completedResult.ping,
                                downloadLoadedLatencyMs = completedResult.downloadLoadedLatency,
                                uploadLoadedLatencyMs = completedResult.uploadLoadedLatency,
                                grade = completedResult.bufferbloatGrade,
                            )
                            EstimatedRatingsCard(result = completedResult)
                        }
                    }
                }

                ConnectionInfoLine(
                    connectionType = uiState.connectionType,
                    networkName = uiState.networkName,
                    cellularNetworkTag = uiState.cellularNetworkTag,
                    deviceModel = uiState.deviceModel,
                    wifiBand = uiState.wifiBand,
                    wifiSsidHidden = uiState.wifiSsidHidden,
                    wifiAccessIssue = wifiAccessIssue,
                    onShowWifiNameClick = handleShowWifiName,
                    onOpenWifiSettingsClick = handleOpenWifiSettings,
                    showNetworkTypePrompt = showNetworkTypePrompt,
                    onShowNetworkTypeClick = handleShowNetworkType,
                    modifier = Modifier.widthIn(max = 560.dp),
                )

                Spacer(modifier = Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun TestHeaderRow(
    uiState: SpeedTestUiState,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.width(12.dp))
        ConnectionBadge(
            connectionType = uiState.connectionType,
            wifiBand = uiState.wifiBand,
            cellularNetworkTag = uiState.cellularNetworkTag,
        )
    }
}

@Composable
private fun TestActionRow(
    isRunning: Boolean,
    completedResult: TestResult?,
    isSharing: Boolean,
    onStartClick: () -> Unit,
    onCancelClick: () -> Unit,
    onShareClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val showShare = !isRunning && completedResult != null
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TestActionButton(
            isRunning = isRunning,
            hasCompleted = completedResult != null,
            onStartClick = onStartClick,
            onCancelClick = onCancelClick,
            modifier = if (showShare) {
                Modifier.weight(1f)
            } else {
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = 260.dp)
            },
        )
        if (showShare) {
            FilledTonalButton(
                onClick = onShareClick,
                enabled = !isSharing,
                modifier = Modifier
                    .heightIn(min = 56.dp)
                    .testTag("share_result_button"),
                shape = RoundedCornerShape(28.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Share,
                    contentDescription = stringResource(R.string.cd_share_result),
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.action_share),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@Composable
private fun TestActionButton(
    isRunning: Boolean,
    hasCompleted: Boolean,
    onStartClick: () -> Unit,
    onCancelClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor by animateColorAsState(
        targetValue = if (isRunning) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.primary
        },
        label = "actionBtnContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = if (isRunning) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onPrimary
        },
        label = "actionBtnContent",
    )

    val label = when {
        isRunning -> stringResource(R.string.action_cancel)
        hasCompleted -> stringResource(R.string.action_run_again)
        else -> stringResource(R.string.action_start)
    }

    val icon = when {
        isRunning -> SpeedTestIcons.Stop
        hasCompleted -> Icons.Rounded.Refresh
        else -> Icons.Rounded.PlayArrow
    }

    Button(
        onClick = if (isRunning) onCancelClick else onStartClick,
        modifier = modifier
            .heightIn(min = 56.dp)
            .testTag(if (isRunning) "cancel_test_button" else "start_test_button"),
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun ErrorBanner(
    error: SpeedTestError?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = error != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier.fillMaxWidth(),
    ) {
        if (error != null) {
            val message = when (error) {
                SpeedTestError.NoInternet -> stringResource(R.string.error_no_internet)
                SpeedTestError.Timeout -> stringResource(R.string.error_timeout)
                SpeedTestError.ServerUnreachable -> stringResource(R.string.error_server_unreachable)
                SpeedTestError.NetworkLost -> stringResource(R.string.error_network_lost)
                SpeedTestError.NetworkChanged -> stringResource(R.string.error_network_changed)
                SpeedTestError.Unknown -> stringResource(R.string.error_unknown)
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("error_banner"),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Warning,
                        contentDescription = stringResource(R.string.cd_error_icon),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("dismiss_error_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.action_dismiss),
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }
    }
}
