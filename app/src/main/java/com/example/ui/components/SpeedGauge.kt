package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.models.TestStage
import com.example.data.settings.SpeedUnit
import com.example.ui.theme.DownloadColor
import com.example.ui.theme.GaugeValueTextStyle
import com.example.ui.theme.UploadColor
import com.example.viewmodel.GaugeDisplayMetric

private const val GAUGE_START_ANGLE = 135f
private const val GAUGE_SWEEP_ANGLE = 270f
private val GAUGE_STROKE_WIDTH = 16.dp
private val SPEED_SCALE_STOPS = doubleArrayOf(0.0, 5.0, 10.0, 25.0, 50.0, 100.0, 250.0, 500.0, 1000.0)

/**
 * Maps Mbps onto [0f, 1f] using a piecewise perceptual scale so both 10 Mbps mobile links
 * and 1000 Mbps fiber links produce expressive, readable arc travel.
 */
internal fun speedToGaugeFraction(speedMbps: Double): Float {
    if (speedMbps <= 0.0) return 0f
    val maxStop = SPEED_SCALE_STOPS.last()
    if (speedMbps >= maxStop) return 1f
    val segmentCount = SPEED_SCALE_STOPS.size - 1
    for (i in 0 until segmentCount) {
        val low = SPEED_SCALE_STOPS[i]
        val high = SPEED_SCALE_STOPS[i + 1]
        if (speedMbps <= high) {
            val localFraction = ((speedMbps - low) / (high - low)).toFloat()
            return ((i + localFraction) / segmentCount).coerceIn(0f, 1f)
        }
    }
    return 1f
}

@Composable
fun SpeedGauge(
    speedMbps: Double,
    stage: TestStage,
    isWarmingUp: Boolean,
    stageProgress: Float,
    livePingMs: Double?,
    speedUnit: SpeedUnit = SpeedUnit.MBPS,
    completedMetric: GaugeDisplayMetric = GaugeDisplayMetric.DOWNLOAD,
    onToggleMetric: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val isCompletedToggleable = stage == TestStage.COMPLETED && onToggleMetric != null

    val targetFraction = when (stage) {
        TestStage.PING -> stageProgress.coerceIn(0f, 1f)
        TestStage.DOWNLOAD, TestStage.UPLOAD, TestStage.COMPLETED -> speedToGaugeFraction(speedMbps)
        TestStage.IDLE -> 0f
    }

    // Keep State<Float> reference and defer reading .value to Canvas draw phase so animation frames
    // never trigger recomposition of the gauge container or text children.
    val animatedFractionState = animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "gaugeFraction",
    )

    val density = LocalDensity.current
    val strokeWidthPx = remember(density) { with(density) { GAUGE_STROKE_WIDTH.toPx() } }
    val strokeStyle = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
    }

    val downloadColor = DownloadColor
    val uploadColor = UploadColor
    val defaultStageLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val defaultValueColor = MaterialTheme.colorScheme.onSurface

    val targetArcColor = when {
        stage == TestStage.DOWNLOAD -> downloadColor
        stage == TestStage.UPLOAD -> uploadColor
        stage == TestStage.COMPLETED && completedMetric == GaugeDisplayMetric.UPLOAD -> uploadColor
        stage == TestStage.COMPLETED -> downloadColor
        else -> MaterialTheme.colorScheme.primary
    }

    val targetStageLabelColor = when {
        stage == TestStage.DOWNLOAD -> downloadColor
        stage == TestStage.UPLOAD -> uploadColor
        stage == TestStage.COMPLETED && completedMetric == GaugeDisplayMetric.UPLOAD -> uploadColor
        stage == TestStage.COMPLETED -> downloadColor
        else -> defaultStageLabelColor
    }

    val targetValueColor = when {
        stage == TestStage.DOWNLOAD -> downloadColor
        stage == TestStage.UPLOAD -> uploadColor
        stage == TestStage.COMPLETED && completedMetric == GaugeDisplayMetric.UPLOAD -> uploadColor
        stage == TestStage.COMPLETED -> downloadColor
        else -> defaultValueColor
    }

    val progressColor by animateColorAsState(
        targetValue = targetArcColor,
        animationSpec = tween(durationMillis = 280),
        label = "gaugeArcColor",
    )
    val stageLabelColor by animateColorAsState(
        targetValue = targetStageLabelColor,
        animationSpec = tween(durationMillis = 280),
        label = "gaugeStageLabelColor",
    )
    val valueTextColor by animateColorAsState(
        targetValue = targetValueColor,
        animationSpec = tween(durationMillis = 280),
        label = "gaugeValueTextColor",
    )

    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)

    val formattedSpeed = formatSpeedValue(speedMbps, speedUnit)
    val unitLabel = speedUnitLabel(speedUnit)
    val gaugeCd = stringResource(R.string.cd_speed_gauge, formattedSpeed, unitLabel)

    val stageCaption = when {
        stage == TestStage.IDLE -> stringResource(R.string.stage_idle)
        stage == TestStage.PING -> stringResource(R.string.stage_ping)
        stage == TestStage.DOWNLOAD && isWarmingUp -> stringResource(R.string.stage_warming_up)
        stage == TestStage.DOWNLOAD -> stringResource(R.string.stage_download)
        stage == TestStage.UPLOAD && isWarmingUp -> stringResource(R.string.stage_warming_up)
        stage == TestStage.UPLOAD -> stringResource(R.string.stage_upload)
        completedMetric == GaugeDisplayMetric.UPLOAD -> stringResource(R.string.metric_upload)
        else -> stringResource(R.string.metric_download)
    }

    val tapModifier = if (isCompletedToggleable) {
        Modifier
            .clip(CircleShape)
            .clickable(
                role = Role.Button,
                onClickLabel = if (completedMetric == GaugeDisplayMetric.DOWNLOAD) {
                    stringResource(R.string.gauge_tap_for_upload)
                } else {
                    stringResource(R.string.gauge_tap_for_download)
                },
                onClick = { onToggleMetric?.invoke() },
            )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .sizeIn(minWidth = 224.dp, maxWidth = 264.dp, minHeight = 224.dp, maxHeight = 264.dp)
            .aspectRatio(1f)
            .then(tapModifier)
            .testTag("speed_gauge")
            .semantics { contentDescription = gaugeCd },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val inset = strokeWidthPx / 2f
            val arcDiameter = (size.minDimension - inset * 2f).coerceAtLeast(0f)
            val topLeft = Offset((size.width - arcDiameter) / 2f, (size.height - arcDiameter) / 2f)
            val arcSize = Size(arcDiameter, arcDiameter)

            drawArc(
                color = trackColor,
                startAngle = GAUGE_START_ANGLE,
                sweepAngle = GAUGE_SWEEP_ANGLE,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = strokeStyle,
            )

            val sweep = (GAUGE_SWEEP_ANGLE * animatedFractionState.value)
                .coerceIn(0f, GAUGE_SWEEP_ANGLE)
            if (sweep > 0.5f) {
                drawArc(
                    color = progressColor,
                    startAngle = GAUGE_START_ANGLE,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = strokeStyle,
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stageCaption,
                style = MaterialTheme.typography.labelMedium,
                color = stageLabelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("gauge_stage_label"),
            )

            Spacer(modifier = Modifier.height(6.dp))

            val primaryValue = if (stage == TestStage.PING && livePingMs != null) {
                formatLatencyReadout(livePingMs)
            } else {
                formattedSpeed
            }
            val primaryUnit = if (stage == TestStage.PING && livePingMs != null) {
                stringResource(R.string.unit_ms)
            } else {
                unitLabel
            }

            Text(
                text = primaryValue,
                style = GaugeValueTextStyle,
                color = valueTextColor,
                maxLines = 1,
                modifier = Modifier.testTag("gauge_value_text"),
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = primaryUnit,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )

            if (isCompletedToggleable) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (completedMetric == GaugeDisplayMetric.DOWNLOAD) {
                        stringResource(R.string.gauge_tap_for_upload)
                    } else {
                        stringResource(R.string.gauge_tap_for_download)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.testTag("gauge_toggle_hint"),
                )
            }
        }
    }
}
