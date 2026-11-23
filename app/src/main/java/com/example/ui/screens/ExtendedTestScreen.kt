package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.models.ConnectionType
import com.example.data.models.SpeedSample
import com.example.data.models.SpeedTestError
import com.example.data.models.TestStage
import com.example.data.settings.SpeedUnit
import com.example.engine.tools.ExtendedDuration
import com.example.engine.tools.PhaseSummaryStats
import com.example.ui.components.ConnectionBadge
import com.example.ui.components.KeepScreenOnEffect
import com.example.ui.components.LiveSpeedGraph
import com.example.ui.components.SpeedTestIcons
import com.example.ui.components.formatSpeedValue
import com.example.ui.components.speedUnitLabel
import com.example.ui.theme.DownloadColor
import com.example.ui.theme.JetBrainsMono
import com.example.ui.theme.UploadColor
import com.example.viewmodel.ExtendedTestUiState
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtendedTestScreen(
    uiState: ExtendedTestUiState,
    downloadSamples: List<SpeedSample>,
    uploadSamples: List<SpeedSample>,
    speedUnit: SpeedUnit,
    onNavigateBack: () -> Unit,
    onSelectDuration: (ExtendedDuration) -> Unit,
    onStartClick: () -> Unit,
    onCancelClick: () -> Unit,
    onConfirmMobileData: () -> Unit,
    onDismissMobileData: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keep screen awake strictly while an extended test is actively running.
    KeepScreenOnEffect(enabled = uiState.isRunning)

    val scrollState = rememberScrollState()

    if (uiState.showMobileDataWarning) {
        ExtendedMobileDataWarningDialog(
            phaseSeconds = uiState.selectedDuration.seconds,
            estimatedMb = uiState.estimatedDataMb,
            onRunAnyway = onConfirmMobileData,
            onCancel = onDismissMobileData,
        )
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("extended_test_screen"),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.extended_test_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("extended_test_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.cd_navigate_back),
                        )
                    }
                },
                actions = {
                    ConnectionBadge(
                        connectionType = uiState.connectionType,
                        wifiBand = uiState.wifiBand,
                        cellularNetworkTag = uiState.cellularNetworkTag,
                        modifier = Modifier.padding(end = 12.dp),
                    )
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 600.dp)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ExtendedStatusBanner(
                    error = uiState.error,
                    wasCancelled = uiState.wasCancelled,
                    hasPartialData = downloadSamples.isNotEmpty() || uploadSamples.isNotEmpty(),
                    onDismiss = onDismissError,
                )

                // Phase duration selector (30s or 60s)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.extended_test_duration_label),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FilterChip(
                                selected = uiState.selectedDuration == ExtendedDuration.THIRTY_SECONDS,
                                onClick = { onSelectDuration(ExtendedDuration.THIRTY_SECONDS) },
                                enabled = !uiState.isRunning,
                                label = { Text(text = stringResource(R.string.extended_test_duration_30s)) },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("extended_duration_30s"),
                            )
                            FilterChip(
                                selected = uiState.selectedDuration == ExtendedDuration.SIXTY_SECONDS,
                                onClick = { onSelectDuration(ExtendedDuration.SIXTY_SECONDS) },
                                enabled = !uiState.isRunning,
                                label = { Text(text = stringResource(R.string.extended_test_duration_60s)) },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("extended_duration_60s"),
                            )
                        }
                        Text(
                            text = stringResource(
                                R.string.extended_test_duration_per_phase,
                                uiState.selectedDuration.seconds,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Estimated data usage card
                DataEstimateCard(
                    uiState = uiState,
                    speedUnit = speedUnit,
                )

                // Mobile data inline notice before starting
                if (uiState.connectionType == ConnectionType.MOBILE) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("extended_mobile_warning_banner"),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Warning,
                                contentDescription = stringResource(R.string.cd_warning_icon),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = stringResource(R.string.extended_test_mobile_banner),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }

                // Start / Cancel / Run again action button
                ExtendedActionButton(
                    isRunning = uiState.isRunning,
                    canRunAgain = uiState.canRunAgain &&
                        downloadSamples.isNotEmpty() &&
                        uploadSamples.isNotEmpty(),
                    onStartClick = onStartClick,
                    onCancelClick = onCancelClick,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Compact live readout directly above the throughput graphs while running
                AnimatedVisibility(
                    visible = uiState.isRunning,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    LivePhaseReadoutCard(
                        uiState = uiState,
                        speedUnit = speedUnit,
                    )
                }

                // Download live throughput graph (shows "Waiting..." before starting, dashed average line after)
                LiveSpeedGraph(
                    samples = downloadSamples,
                    graphStage = TestStage.DOWNLOAD,
                    speedUnit = speedUnit,
                    phaseDurationSeconds = uiState.phaseDurationSeconds,
                    averageMbps = uiState.downloadSummary?.averageMbps,
                    isActivePhase = uiState.isRunning && uiState.stage == TestStage.DOWNLOAD,
                    showTimeAxis = true,
                    emptyHintOverride = stringResource(R.string.extended_test_graph_waiting),
                    modifier = Modifier.testTag("extended_download_graph"),
                )

                // Upload live throughput graph (shows "Waiting..." before starting, dashed average line after)
                LiveSpeedGraph(
                    samples = uploadSamples,
                    graphStage = TestStage.UPLOAD,
                    speedUnit = speedUnit,
                    phaseDurationSeconds = uiState.phaseDurationSeconds,
                    averageMbps = uiState.uploadSummary?.averageMbps,
                    isActivePhase = uiState.isRunning && uiState.stage == TestStage.UPLOAD,
                    showTimeAxis = true,
                    emptyHintOverride = stringResource(R.string.extended_test_graph_waiting),
                    modifier = Modifier.testTag("extended_upload_graph"),
                )

                // Stability summary cards (Average, Minimum, Maximum, Stability %, excluding 1.5s warm-up)
                AnimatedVisibility(
                    visible = uiState.downloadSummary != null || uiState.uploadSummary != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("extended_stability_summary_section"),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.extended_test_summary_title),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp),
                        )

                        uiState.downloadSummary?.let { summary ->
                            PhaseSummaryCard(
                                title = stringResource(R.string.metric_download),
                                icon = SpeedTestIcons.ArrowDown,
                                accentColor = DownloadColor,
                                summary = summary,
                                speedUnit = speedUnit,
                                testTag = "extended_summary_download",
                            )
                        }

                        uiState.uploadSummary?.let { summary ->
                            PhaseSummaryCard(
                                title = stringResource(R.string.metric_upload),
                                icon = SpeedTestIcons.ArrowUp,
                                accentColor = UploadColor,
                                summary = summary,
                                speedUnit = speedUnit,
                                testTag = "extended_summary_upload",
                            )
                        }

                        Text(
                            text = stringResource(R.string.extended_test_stability_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.extended_test_not_saved_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun DataEstimateCard(
    uiState: ExtendedTestUiState,
    speedUnit: SpeedUnit,
) {
    val estimatedMb = uiState.estimatedDataMb
    val unitLabel = speedUnitLabel(speedUnit)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("extended_data_estimate_card"),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.extended_test_estimate_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (estimatedMb != null) {
                    Text(
                        text = stringResource(
                            R.string.extended_test_estimate_value,
                            formatEstimatedMb(estimatedMb),
                        ),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = JetBrainsMono,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("extended_data_estimate_value"),
                    )
                }
            }

            val subtitle = if (estimatedMb != null) {
                val dlText = uiState.lastDownloadMbps?.let { "${formatSpeedValue(it, speedUnit)} $unitLabel" } ?: "-"
                val ulText = uiState.lastUploadMbps?.let { "${formatSpeedValue(it, speedUnit)} $unitLabel" } ?: "-"
                stringResource(R.string.extended_test_estimate_basis, dlText, ulText)
            } else {
                stringResource(R.string.extended_test_estimate_unavailable)
            }

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LivePhaseReadoutCard(
    uiState: ExtendedTestUiState,
    speedUnit: SpeedUnit,
) {
    val totalSeconds = uiState.runDurationSeconds.coerceAtLeast(1)
    val clampedElapsed = uiState.elapsedSeconds.coerceIn(0, totalSeconds)
    val remainingSeconds = uiState.remainingSeconds
    val progress = (clampedElapsed.toFloat() / totalSeconds).coerceIn(0f, 1f)

    val phaseName = if (uiState.stage == TestStage.UPLOAD) {
        stringResource(R.string.extended_test_phase_upload)
    } else {
        stringResource(R.string.extended_test_phase_download)
    }
    val phaseLabel = if (uiState.isWarmingUp) {
        "$phaseName · ${stringResource(R.string.extended_test_phase_warming)}"
    } else {
        phaseName
    }
    val remainingLabel = stringResource(R.string.extended_test_seconds_remaining, remainingSeconds)

    val phaseColor = animateColorAsState(
        targetValue = if (uiState.stage == TestStage.UPLOAD) UploadColor else DownloadColor,
        label = "extendedPhaseColor",
    ).value

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("extended_live_status_card"),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    Text(
                        text = phaseLabel,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = phaseColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Text(
                        text = remainingLabel,
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMono),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Text(
                    text = "${formatSpeedValue(uiState.liveSpeedMbps, speedUnit)} ${speedUnitLabel(speedUnit)}",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = JetBrainsMono,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = phaseColor,
                    maxLines = 1,
                )
            }
            LinearProgressIndicator(
                progress = { progress },
                color = phaseColor,
                trackColor = phaseColor.copy(alpha = 0.18f),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PhaseSummaryCard(
    title: String,
    icon: ImageVector,
    accentColor: Color,
    summary: PhaseSummaryStats,
    speedUnit: SpeedUnit,
    testTag: String,
) {
    val unitLabel = speedUnitLabel(speedUnit)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = accentColor,
                )
            }
            SummaryStatRow(
                label = stringResource(R.string.extended_test_stat_average),
                value = "${formatSpeedValue(summary.averageMbps, speedUnit)} $unitLabel",
                valueColor = accentColor,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            SummaryStatRow(
                label = stringResource(R.string.extended_test_stat_min),
                value = "${formatSpeedValue(summary.minMbps, speedUnit)} $unitLabel",
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            SummaryStatRow(
                label = stringResource(R.string.extended_test_stat_max),
                value = "${formatSpeedValue(summary.maxMbps, speedUnit)} $unitLabel",
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            SummaryStatRow(
                label = stringResource(R.string.extended_test_stat_stability),
                value = stringResource(R.string.extended_test_stability_percent, summary.stabilityPercent),
                valueColor = accentColor,
            )
        }
    }
}

@Composable
private fun SummaryStatRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall.copy(
                fontFamily = JetBrainsMono,
                fontWeight = FontWeight.SemiBold,
            ),
            color = valueColor,
        )
    }
}

@Composable
private fun ExtendedActionButton(
    isRunning: Boolean,
    canRunAgain: Boolean,
    onStartClick: () -> Unit,
    onCancelClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = animateColorAsState(
        targetValue = if (isRunning) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.primary
        },
        label = "extendedBtnContainer",
    ).value
    val contentColor = animateColorAsState(
        targetValue = if (isRunning) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onPrimary
        },
        label = "extendedBtnContent",
    ).value

    val label = when {
        isRunning -> stringResource(R.string.action_cancel)
        canRunAgain -> stringResource(R.string.action_run_again)
        else -> stringResource(R.string.action_start)
    }
    val icon = when {
        isRunning -> SpeedTestIcons.Stop
        canRunAgain -> Icons.Rounded.Refresh
        else -> Icons.Rounded.PlayArrow
    }

    Button(
        onClick = if (isRunning) onCancelClick else onStartClick,
        modifier = modifier
            .heightIn(min = 54.dp)
            .testTag(if (isRunning) "extended_cancel_button" else "extended_start_button"),
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
private fun ExtendedMobileDataWarningDialog(
    phaseSeconds: Int,
    estimatedMb: Double?,
    onRunAnyway: () -> Unit,
    onCancel: () -> Unit,
) {
    val estimateSuffix = estimatedMb?.let {
        stringResource(R.string.extended_test_mobile_dialog_estimate, formatEstimatedMb(it))
    }.orEmpty()

    AlertDialog(
        onDismissRequest = onCancel,
        shape = RoundedCornerShape(28.dp),
        icon = {
            Icon(
                imageVector = Icons.Rounded.Warning,
                contentDescription = stringResource(R.string.cd_warning_icon),
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text(
                text = stringResource(R.string.mobile_data_warning_title),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Text(
                text = stringResource(
                    R.string.extended_test_mobile_dialog_body,
                    phaseSeconds,
                    estimateSuffix,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            Button(
                onClick = onRunAnyway,
                modifier = Modifier.testTag("extended_mobile_run_anyway_button"),
            ) {
                Text(text = stringResource(R.string.action_run_anyway))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.testTag("extended_mobile_cancel_button"),
            ) {
                Text(text = stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun ExtendedStatusBanner(
    error: SpeedTestError?,
    wasCancelled: Boolean,
    hasPartialData: Boolean,
    onDismiss: () -> Unit,
) {
    val showBanner = error != null || wasCancelled
    AnimatedVisibility(
        visible = showBanner,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
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
                    .testTag("extended_error_banner"),
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
                        modifier = Modifier.testTag("extended_dismiss_error_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.action_dismiss),
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        } else if (wasCancelled) {
            val message = if (hasPartialData) {
                stringResource(R.string.extended_test_cancelled_partial)
            } else {
                stringResource(R.string.extended_test_cancelled_empty)
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("extended_cancelled_banner"),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
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
                        contentDescription = stringResource(R.string.cd_warning_icon),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("extended_dismiss_cancelled_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.action_dismiss),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        }
    }
}

private fun formatEstimatedMb(mb: Double): String = if (mb >= 100.0) {
    String.format(Locale.US, "%.0f", mb)
} else {
    String.format(Locale.US, "%.1f", mb)
}
