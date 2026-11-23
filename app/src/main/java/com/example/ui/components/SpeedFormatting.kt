package com.example.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.R
import com.example.data.settings.SpeedUnit
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private const val BITS_PER_BYTE = 8.0

fun convertSpeedFromMbps(speedMbps: Double, unit: SpeedUnit): Double {
    return when (unit) {
        SpeedUnit.MBPS -> speedMbps
        SpeedUnit.MB_PER_SEC -> speedMbps / BITS_PER_BYTE
    }
}

fun formatSpeedValue(speedMbps: Double, unit: SpeedUnit): String {
    val converted = convertSpeedFromMbps(speedMbps, unit)
    return when {
        converted <= 0.0 -> "0.0"
        converted >= 1000.0 -> String.format(Locale.US, "%.0f", converted)
        converted >= 10.0 -> String.format(Locale.US, "%.1f", converted)
        else -> String.format(Locale.US, "%.2f", converted)
    }
}

fun formatLatencyReadout(latencyMs: Double?): String {
    if (latencyMs == null || !latencyMs.isFinite() || latencyMs < 0.0) return "-"
    return if (latencyMs >= 100.0) {
        String.format(Locale.US, "%.0f", latencyMs)
    } else {
        String.format(Locale.US, "%.1f", latencyMs)
    }
}

fun formatJitterReadout(jitterMs: Double?): String {
    if (jitterMs == null || !jitterMs.isFinite() || jitterMs < 0.0) return "-"
    return String.format(Locale.US, "%.1f", jitterMs)
}

@Composable
fun speedUnitLabel(unit: SpeedUnit): String {
    return when (unit) {
        SpeedUnit.MBPS -> stringResource(R.string.unit_mbps)
        SpeedUnit.MB_PER_SEC -> stringResource(R.string.unit_mb_per_sec)
    }
}

fun formatTimestampMedium(timestampMillis: Long): String {
    val zoned = Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault())
    val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    return formatter.format(zoned)
}
