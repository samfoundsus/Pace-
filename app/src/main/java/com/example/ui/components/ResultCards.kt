package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.settings.SpeedUnit
import com.example.ui.theme.DownloadColor
import com.example.ui.theme.JetBrainsMono
import com.example.ui.theme.MetricValueTextStyle
import com.example.ui.theme.UploadColor

/**
 * Ookla-style single horizontal latency row directly under the gauge:
 * Ping (idle) | Download ping (loaded) | Upload ping (loaded) | Jitter.
 * Each metric is shown in ms with a small colored icon, displaying "-" when null (never 0).
 */
@Composable
fun OoklaLatencyRow(
    idlePingMs: Double?,
    downloadPingMs: Double?,
    uploadPingMs: Double?,
    jitterMs: Double?,
    modifier: Modifier = Modifier,
) {
    val rowCd = stringResource(R.string.cd_latency_row)
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("ookla_latency_row")
            .semantics { contentDescription = rowCd },
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LatencyMetricSlot(
                icon = SpeedTestIcons.Ping,
                iconTint = MaterialTheme.colorScheme.secondary,
                label = stringResource(R.string.metric_ping),
                valueMs = idlePingMs,
                modifier = Modifier
                    .weight(1f)
                    .testTag("latency_slot_ping"),
            )
            VerticalDivider(
                modifier = Modifier
                    .fillMaxHeight(0.65f)
                    .padding(horizontal = 2.dp),
                color = dividerColor,
            )
            LatencyMetricSlot(
                icon = SpeedTestIcons.ArrowDown,
                iconTint = DownloadColor,
                label = stringResource(R.string.metric_dl_ping),
                valueMs = downloadPingMs,
                modifier = Modifier
                    .weight(1f)
                    .testTag("latency_slot_download_ping"),
            )
            VerticalDivider(
                modifier = Modifier
                    .fillMaxHeight(0.65f)
                    .padding(horizontal = 2.dp),
                color = dividerColor,
            )
            LatencyMetricSlot(
                icon = SpeedTestIcons.ArrowUp,
                iconTint = UploadColor,
                label = stringResource(R.string.metric_ul_ping),
                valueMs = uploadPingMs,
                modifier = Modifier
                    .weight(1f)
                    .testTag("latency_slot_upload_ping"),
            )
            VerticalDivider(
                modifier = Modifier
                    .fillMaxHeight(0.65f)
                    .padding(horizontal = 2.dp),
                color = dividerColor,
            )
            LatencyMetricSlot(
                icon = SpeedTestIcons.Jitter,
                iconTint = MaterialTheme.colorScheme.secondary,
                label = stringResource(R.string.metric_jitter),
                valueMs = jitterMs,
                formattedValue = formatJitterReadout(jitterMs),
                modifier = Modifier
                    .weight(1f)
                    .testTag("latency_slot_jitter"),
            )
        }
    }
}

@Composable
private fun LatencyMetricSlot(
    icon: ImageVector,
    iconTint: Color,
    label: String,
    valueMs: Double?,
    formattedValue: String = formatLatencyReadout(valueMs),
    modifier: Modifier = Modifier,
) {
    val unitMs = stringResource(R.string.unit_ms)
    val hasValidValue = valueMs != null && valueMs.isFinite() && valueMs >= 0.0

    Column(
        modifier = modifier.padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(13.dp),
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.height(5.dp))

        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = formattedValue,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontFamily = JetBrainsMono,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (hasValidValue) {
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = unitMs,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Throughput result cards (Download & Upload). Latency metrics live in [OoklaLatencyRow].
 */
@Composable
fun ThroughputCardsRow(
    downloadMbps: Double?,
    uploadMbps: Double?,
    speedUnit: SpeedUnit = SpeedUnit.MBPS,
    modifier: Modifier = Modifier,
) {
    val unavailable = stringResource(R.string.metric_unavailable)
    val speedLabel = speedUnitLabel(speedUnit)
    val downloadColor = DownloadColor
    val uploadColor = UploadColor

    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("result_cards_grid"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MetricResultCard(
            label = stringResource(R.string.metric_download),
            value = downloadMbps?.let { formatSpeedValue(it, speedUnit) } ?: unavailable,
            unit = if (downloadMbps != null) speedLabel else null,
            icon = SpeedTestIcons.ArrowDown,
            iconBg = downloadColor.copy(alpha = 0.14f),
            iconTint = downloadColor,
            valueColor = downloadColor,
            modifier = Modifier
                .weight(1f)
                .testTag("result_card_download"),
        )
        MetricResultCard(
            label = stringResource(R.string.metric_upload),
            value = uploadMbps?.let { formatSpeedValue(it, speedUnit) } ?: unavailable,
            unit = if (uploadMbps != null) speedLabel else null,
            icon = SpeedTestIcons.ArrowUp,
            iconBg = uploadColor.copy(alpha = 0.14f),
            iconTint = uploadColor,
            valueColor = uploadColor,
            modifier = Modifier
                .weight(1f)
                .testTag("result_card_upload"),
        )
    }
}

@Composable
private fun MetricResultCard(
    label: String,
    value: String,
    unit: String?,
    icon: ImageVector,
    iconBg: Color,
    iconTint: Color,
    valueColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(iconBg),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = value,
                    style = MetricValueTextStyle,
                    color = valueColor,
                    maxLines = 1,
                )
                if (unit != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
        }
    }
}
