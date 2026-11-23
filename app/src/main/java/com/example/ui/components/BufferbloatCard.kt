package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.models.BufferbloatGrade
import com.example.ui.theme.JetBrainsMono

@Composable
fun BufferbloatCard(
    idlePingMs: Double?,
    downloadLoadedLatencyMs: Double?,
    uploadLoadedLatencyMs: Double?,
    grade: BufferbloatGrade?,
    modifier: Modifier = Modifier,
) {
    val gradeContainerColor = when (grade) {
        BufferbloatGrade.A -> MaterialTheme.colorScheme.tertiaryContainer
        BufferbloatGrade.B -> MaterialTheme.colorScheme.primaryContainer
        BufferbloatGrade.C -> MaterialTheme.colorScheme.secondaryContainer
        BufferbloatGrade.D, BufferbloatGrade.F -> MaterialTheme.colorScheme.errorContainer
        null -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val gradeContentColor = when (grade) {
        BufferbloatGrade.A -> MaterialTheme.colorScheme.onTertiaryContainer
        BufferbloatGrade.B -> MaterialTheme.colorScheme.onPrimaryContainer
        BufferbloatGrade.C -> MaterialTheme.colorScheme.onSecondaryContainer
        BufferbloatGrade.D, BufferbloatGrade.F -> MaterialTheme.colorScheme.onErrorContainer
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val explanation = when (grade) {
        BufferbloatGrade.A -> stringResource(R.string.bufferbloat_desc_a)
        BufferbloatGrade.B -> stringResource(R.string.bufferbloat_desc_b)
        BufferbloatGrade.C -> stringResource(R.string.bufferbloat_desc_c)
        BufferbloatGrade.D -> stringResource(R.string.bufferbloat_desc_d)
        BufferbloatGrade.F -> stringResource(R.string.bufferbloat_desc_f)
        null -> stringResource(R.string.bufferbloat_desc_unavailable)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("bufferbloat_card"),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = SpeedTestIcons.Bufferbloat,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.bufferbloat_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = gradeContainerColor,
                    modifier = Modifier.testTag("bufferbloat_grade_badge"),
                ) {
                    Text(
                        text = if (grade != null) {
                            stringResource(R.string.bufferbloat_grade_label, grade.name)
                        } else {
                            stringResource(R.string.bufferbloat_grade_unavailable)
                        },
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = gradeContentColor,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp, horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    LatencyTriMetric(
                        label = stringResource(R.string.bufferbloat_idle_ping),
                        valueMs = idlePingMs,
                        modifier = Modifier.weight(1f),
                    )
                    LatencyTriMetric(
                        label = stringResource(R.string.bufferbloat_download_ping),
                        valueMs = downloadLoadedLatencyMs,
                        modifier = Modifier.weight(1f),
                    )
                    LatencyTriMetric(
                        label = stringResource(R.string.bufferbloat_upload_ping),
                        valueMs = uploadLoadedLatencyMs,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = explanation,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LatencyTriMetric(
    label: String,
    valueMs: Double?,
    modifier: Modifier = Modifier,
) {
    val unitMs = stringResource(R.string.unit_ms)
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = formatLatencyReadout(valueMs),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = JetBrainsMono,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (valueMs != null) {
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = unitMs,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
    }
}
