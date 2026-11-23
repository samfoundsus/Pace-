package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.models.SpeedSample
import com.example.data.models.TestStage
import com.example.data.settings.SpeedUnit
import com.example.engine.tools.ExtendedTestStats
import com.example.ui.theme.DownloadColor
import com.example.ui.theme.JetBrainsMono
import com.example.ui.theme.UploadColor
import kotlin.math.roundToInt

@Composable
fun LiveSpeedGraph(
    samples: List<SpeedSample>,
    graphStage: TestStage,
    speedUnit: SpeedUnit = SpeedUnit.MBPS,
    phaseDurationSeconds: Float = 10f,
    averageMbps: Double? = null,
    isActivePhase: Boolean = true,
    showTimeAxis: Boolean = false,
    titleOverride: String? = null,
    emptyHintOverride: String? = null,
    modifier: Modifier = Modifier,
) {
    val lineColor by animateColorAsState(
        targetValue = when (graphStage) {
            TestStage.UPLOAD -> UploadColor
            else -> DownloadColor
        },
        animationSpec = tween(durationMillis = 260),
        label = "graphLineColor",
    )

    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    val graphTitle = titleOverride ?: when (graphStage) {
        TestStage.DOWNLOAD -> stringResource(R.string.graph_title_download)
        TestStage.UPLOAD -> stringResource(R.string.graph_title_upload)
        else -> stringResource(R.string.graph_title_idle)
    }
    val emptyHint = emptyHintOverride ?: stringResource(R.string.graph_empty_hint)
    val graphCd = stringResource(R.string.cd_live_graph)
    val unitLabel = speedUnitLabel(speedUnit)

    val boundedSamples = remember(samples) {
        if (samples.size > ExtendedTestStats.MAX_GRAPH_POINTS) {
            ExtendedTestStats.downsampleSamples(samples, ExtendedTestStats.MAX_GRAPH_POINTS)
        } else {
            samples
        }
    }

    val yAxisCeilingMbps = remember(boundedSamples, averageMbps) {
        val peakSample = boundedSamples.maxOfOrNull { it.speedMbps } ?: 0.0
        ExtendedTestStats.computeYAxisCeiling(
            maxSampleMbps = peakSample,
            averageMbps = averageMbps,
        )
    }

    // Reuse mutable Path and PathEffect instances across frames so onDraw allocates zero objects.
    val linePath = remember { Path() }
    val fillPath = remember { Path() }
    val gridDashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f) }
    val avgDashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(14f, 8f), 0f) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("live_speed_graph"),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = graphTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = lineColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .testTag("graph_title_text"),
                )
                if (boundedSamples.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val validAverage = averageMbps?.takeIf { it.isFinite() && it > 0.0 }
                        if (validAverage != null) {
                            Text(
                                text = stringResource(
                                    R.string.extended_test_graph_avg_label,
                                    formatSpeedValue(validAverage, speedUnit),
                                    unitLabel,
                                ),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = JetBrainsMono,
                                    fontWeight = FontWeight.Medium,
                                ),
                                color = lineColor.copy(alpha = 0.85f),
                                maxLines = 1,
                                modifier = Modifier.testTag("graph_avg_label_text"),
                            )
                        }
                        Text(
                            text = "${formatSpeedValue(yAxisCeilingMbps, speedUnit)} $unitLabel",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = JetBrainsMono,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = lineColor,
                            maxLines = 1,
                            modifier = Modifier.testTag("graph_max_label_text"),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(116.dp)
                    .semantics { contentDescription = graphCd },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawWithCache {
                            val width = size.width
                            val height = size.height
                            val topPadding = 8.dp.toPx()
                            val bottomPadding = 6.dp.toPx()
                            val drawableHeight = (height - topPadding - bottomPadding).coerceAtLeast(1f)
                            val baselineY = topPadding + drawableHeight
                            val gridStrokeWidth = 1.dp.toPx()
                            val avgStrokeWidth = 1.75.dp.toPx()
                            val lineStroke = Stroke(
                                width = 2.75.dp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round,
                            )
                            val haloRadius = 7.dp.toPx()
                            val dotRadius = 3.5.dp.toPx()
                            val haloColor = lineColor.copy(alpha = 0.25f)
                            val avgLineColor = lineColor.copy(alpha = 0.85f)
                            val fillBrush = Brush.verticalGradient(
                                colors = listOf(
                                    lineColor.copy(alpha = 0.28f),
                                    lineColor.copy(alpha = 0.02f),
                                ),
                                startY = topPadding,
                                endY = baselineY,
                            )

                            linePath.reset()
                            fillPath.reset()

                            var hasData = false
                            var headX = 0f
                            var headY = baselineY

                            val count = boundedSamples.size
                            if (count > 0 && width > 0f) {
                                hasData = true
                                val lastSample = boundedSamples[count - 1]
                                val maxTime = maxOf(phaseDurationSeconds, lastSample.elapsedSeconds, 0.001f)

                                val first = boundedSamples[0]
                                val firstX = ((first.elapsedSeconds / maxTime).coerceIn(0f, 1f)) * width
                                val firstNormY = (first.speedMbps / yAxisCeilingMbps).toFloat().coerceIn(0f, 1f)
                                val firstY = topPadding + drawableHeight * (1f - firstNormY)

                                linePath.moveTo(firstX, firstY)
                                var prevX = firstX
                                var prevY = firstY

                                if (count == 1) {
                                    linePath.lineTo(firstX + 1f, firstY)
                                    headX = firstX
                                    headY = firstY
                                } else {
                                    for (i in 1 until count) {
                                        val sample = boundedSamples[i]
                                        val currX = ((sample.elapsedSeconds / maxTime).coerceIn(0f, 1f)) * width
                                        val normY = (sample.speedMbps / yAxisCeilingMbps).toFloat().coerceIn(0f, 1f)
                                        val currY = topPadding + drawableHeight * (1f - normY)
                                        val midX = (prevX + currX) * 0.5f
                                        linePath.cubicTo(midX, prevY, midX, currY, currX, currY)
                                        prevX = currX
                                        prevY = currY
                                    }
                                    headX = prevX
                                    headY = prevY
                                }

                                fillPath.addPath(linePath)
                                fillPath.lineTo(headX, baselineY)
                                fillPath.lineTo(firstX, baselineY)
                                fillPath.close()
                            }

                            val validAverage = averageMbps?.takeIf { it.isFinite() && it > 0.0 }
                            val avgY = if (hasData && validAverage != null) {
                                val avgNormY = (validAverage / yAxisCeilingMbps).toFloat().coerceIn(0f, 1f)
                                topPadding + drawableHeight * (1f - avgNormY)
                            } else {
                                null
                            }

                            onDrawBehind {
                                val gridSteps = 3
                                for (step in 0..gridSteps) {
                                    val y = topPadding + drawableHeight * (step.toFloat() / gridSteps)
                                    drawLine(
                                        color = gridColor,
                                        start = Offset(0f, y),
                                        end = Offset(width, y),
                                        strokeWidth = gridStrokeWidth,
                                        pathEffect = if (step == gridSteps) null else gridDashEffect,
                                    )
                                }

                                if (hasData) {
                                    drawPath(path = fillPath, brush = fillBrush)
                                    drawPath(path = linePath, color = lineColor, style = lineStroke)

                                    if (avgY != null) {
                                        drawLine(
                                            color = avgLineColor,
                                            start = Offset(0f, avgY),
                                            end = Offset(width, avgY),
                                            strokeWidth = avgStrokeWidth,
                                            pathEffect = avgDashEffect,
                                        )
                                    }

                                    if (isActivePhase) {
                                        val headCenter = Offset(headX, headY)
                                        drawCircle(color = haloColor, radius = haloRadius, center = headCenter)
                                        drawCircle(color = lineColor, radius = dotRadius, center = headCenter)
                                    }
                                }
                            }
                        },
                )

                if (boundedSamples.isEmpty()) {
                    Text(
                        text = emptyHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("graph_empty_hint_text"),
                    )
                }
            }

            if (showTimeAxis) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.extended_test_graph_axis_zero),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(
                            R.string.extended_test_graph_axis_end,
                            phaseDurationSeconds.roundToInt().coerceAtLeast(1),
                        ),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
