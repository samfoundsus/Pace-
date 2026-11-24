package com.example.ui.screens

import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.models.ConnectionType
import com.example.data.models.TestResult
import com.example.data.settings.SpeedUnit
import com.example.engine.export.ResultCardRenderer
import com.example.engine.export.ResultPdfExporter
import com.example.ui.components.BufferbloatCard
import com.example.ui.components.ConnectionBadge
import com.example.ui.components.ConnectionInfoLine
import com.example.ui.components.EstimatedRatingsCard
import com.example.ui.components.OoklaLatencyRow
import com.example.ui.components.SpeedTestIcons
import com.example.ui.components.ThroughputCardsRow
import com.example.ui.components.formatTimestampMedium
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryDetailScreen(
    result: TestResult?,
    speedUnit: SpeedUnit,
    onNavigateBack: () -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var isSharingCard by remember { mutableStateOf(false) }
    var isExportingPdf by remember { mutableStateOf(false) }
    var showPdfDialog by remember { mutableStateOf(false) }
    var includeNetworkNameInPdf by remember { mutableStateOf(false) }

    val shareChooserTitle = stringResource(R.string.share_chooser_title)
    val shareErrorText = stringResource(R.string.share_card_error)
    val pdfSuccessText = stringResource(R.string.pdf_export_success)
    val pdfErrorText = stringResource(R.string.pdf_export_error)

    val createPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        val currentResult = result ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult

        isExportingPdf = true
        coroutineScope.launch {
            val outcome = ResultPdfExporter.writePdfToUri(
                context = context,
                uri = uri,
                result = currentResult,
                speedUnit = speedUnit,
                includeNetworkName = includeNetworkNameInPdf,
            )
            isExportingPdf = false
            Toast.makeText(
                context,
                if (outcome.isSuccess) pdfSuccessText else pdfErrorText,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    val handleShare: () -> Unit = {
        val currentResult = result
        if (currentResult != null && !isSharingCard) {
            isSharingCard = true
            coroutineScope.launch {
                val outcome = ResultCardRenderer.shareResultCard(
                    context = context,
                    result = currentResult,
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

    if (showPdfDialog && result != null) {
        PdfExportOptionsDialog(
            includeNetworkName = includeNetworkNameInPdf,
            onIncludeNetworkNameChange = { includeNetworkNameInPdf = it },
            onConfirm = {
                showPdfDialog = false
                createPdfLauncher.launch(ResultPdfExporter.suggestedFileName(result.timestamp))
            },
            onDismiss = { showPdfDialog = false },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("history_detail_screen"),
    ) {
        TopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.history_detail_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            },
            navigationIcon = {
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier.testTag("detail_back_button"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.cd_navigate_back),
                    )
                }
            },
            actions = {
                if (result != null) {
                    IconButton(
                        onClick = handleShare,
                        enabled = !isSharingCard,
                        modifier = Modifier.testTag("detail_top_share_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Share,
                            contentDescription = stringResource(R.string.cd_share_result),
                        )
                    }
                    IconButton(
                        onClick = {
                            onDelete(result.id)
                            onNavigateBack()
                        },
                        modifier = Modifier.testTag("detail_delete_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = stringResource(R.string.cd_delete_item),
                        )
                    }
                }
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
        )

        if (result == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.history_item_not_found),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 600.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = formatTimestampMedium(result.timestamp),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        ConnectionBadge(
                            connectionType = result.connectionType,
                            wifiBandLabel = result.wifiBand,
                            cellularNetworkTag = result.cellularNetworkTag,
                        )
                    }

                    val serverName = result.serverName?.trim()?.takeIf { it.isNotEmpty() }
                    if (serverName != null) {
                        val serverLocation = result.serverLocation?.trim()?.takeIf { it.isNotEmpty() }
                        val serverLabel = if (serverLocation != null) {
                            "$serverName · $serverLocation"
                        } else {
                            serverName
                        }
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("detail_server_row"),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
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
                                Text(
                                    text = serverLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }

                    ThroughputCardsRow(
                        downloadMbps = result.download,
                        uploadMbps = result.upload,
                        speedUnit = speedUnit,
                    )

                    OoklaLatencyRow(
                        idlePingMs = result.ping,
                        downloadPingMs = result.downloadLoadedLatency,
                        uploadPingMs = result.uploadLoadedLatency,
                        jitterMs = result.jitter,
                    )

                    // Share card & Export PDF action row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        FilledTonalButton(
                            onClick = handleShare,
                            enabled = !isSharingCard,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("detail_share_button"),
                            shape = RoundedCornerShape(24.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Share,
                                contentDescription = stringResource(R.string.cd_share_result),
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.action_share),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }

                        FilledTonalButton(
                            onClick = {
                                // Always default privacy switch to OFF before opening dialog
                                includeNetworkNameInPdf = false
                                showPdfDialog = true
                            },
                            enabled = !isExportingPdf,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("detail_export_pdf_button"),
                            shape = RoundedCornerShape(24.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Icon(
                                imageVector = SpeedTestIcons.PdfDocument,
                                contentDescription = stringResource(R.string.cd_export_pdf),
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.action_export_pdf),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }

                    BufferbloatCard(
                        idlePingMs = result.ping,
                        downloadLoadedLatencyMs = result.downloadLoadedLatency,
                        uploadLoadedLatencyMs = result.uploadLoadedLatency,
                        grade = result.bufferbloatGrade,
                    )

                    EstimatedRatingsCard(result = result)

                    ConnectionInfoLine(
                        connectionType = result.connectionType,
                        networkName = result.networkName,
                        cellularNetworkTag = result.cellularNetworkTag,
                        deviceModel = result.deviceModel ?: Build.MODEL?.trim(),
                        wifiBandLabel = result.wifiBand,
                        wifiSsidHidden = result.connectionType == ConnectionType.WIFI &&
                            result.networkName.isNullOrBlank(),
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun PdfExportOptionsDialog(
    includeNetworkName: Boolean,
    onIncludeNetworkNameChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.testTag("pdf_export_dialog"),
        title = {
            Text(
                text = stringResource(R.string.pdf_export_dialog_title),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = stringResource(R.string.pdf_export_dialog_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.pdf_export_include_network_name),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.pdf_export_include_network_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = includeNetworkName,
                        onCheckedChange = onIncludeNetworkNameChange,
                        modifier = Modifier.testTag("pdf_include_network_switch"),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                modifier = Modifier.testTag("pdf_export_confirm_button"),
            ) {
                Text(text = stringResource(R.string.action_save_pdf))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("pdf_export_cancel_button"),
            ) {
                Text(text = stringResource(R.string.action_cancel))
            }
        },
    )
}
