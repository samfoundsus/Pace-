package com.example.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.engine.tools.DnsResolverId
import com.example.engine.tools.DnsResolverResult
import com.example.engine.tools.DnsResolverStatus
import com.example.ui.components.SpeedTestIcons
import com.example.ui.theme.JetBrainsMono
import com.example.viewmodel.DnsBenchmarkUiState
import java.util.Locale

private val DnsOuterCorner = 28.dp
private val DnsInnerCorner = 4.dp
private val DnsTileGap = 2.dp

private fun dnsTileShape(index: Int, count: Int): Shape = when {
    count <= 1 -> RoundedCornerShape(DnsOuterCorner)
    index == 0 -> RoundedCornerShape(
        topStart = DnsOuterCorner,
        topEnd = DnsOuterCorner,
        bottomStart = DnsInnerCorner,
        bottomEnd = DnsInnerCorner,
    )
    index == count - 1 -> RoundedCornerShape(
        topStart = DnsInnerCorner,
        topEnd = DnsInnerCorner,
        bottomStart = DnsOuterCorner,
        bottomEnd = DnsOuterCorner,
    )
    else -> RoundedCornerShape(DnsInnerCorner)
}

internal fun formatDnsMedianMs(medianMs: Double?): String? {
    if (medianMs == null || !medianMs.isFinite() || medianMs <= 0.0) return null
    return if (medianMs < 0.1) {
        "<0.1 ms"
    } else {
        String.format(Locale.US, "%.1f ms", medianMs)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DnsBenchmarkScreen(
    uiState: DnsBenchmarkUiState,
    onNavigateBack: () -> Unit,
    onRunClick: () -> Unit,
    onCancelClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rankedResults = uiState.rankedResults
    val fastestId = uiState.fastestResolverId
    val maxMedianMs = uiState.maxMedianMs

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("dns_benchmark_screen"),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.dns_benchmark_title),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("dns_benchmark_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.cd_navigate_back),
                        )
                    }
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
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 600.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // Intro & Run/Cancel action
                item(key = "dns_header_controls") {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.dns_benchmark_intro),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp),
                        )

                        DnsActionButton(
                            isRunning = uiState.isRunning,
                            hasCompleted = uiState.hasCompleted,
                            onRunClick = onRunClick,
                            onCancelClick = onCancelClick,
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 260.dp),
                        )

                        if (uiState.isRunning && uiState.activeResolverId != null) {
                            val activeName = resolverDisplayName(uiState.activeResolverId)
                            val progress = (uiState.activeResolverIndex - 1).coerceAtLeast(0).toFloat() /
                                uiState.totalResolvers.coerceAtLeast(1).toFloat()
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.dns_progress_testing,
                                        activeName,
                                        uiState.activeResolverIndex,
                                        uiState.totalResolvers,
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.testTag("dns_progress_label"),
                                )
                                LinearProgressIndicator(
                                    progress = { progress.coerceIn(0.08f, 1f) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(50)),
                                    strokeCap = StrokeCap.Round,
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                )
                            }
                        }
                    }
                }

                // Ranked resolver results list
                item(key = "dns_results_group") {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(DnsTileGap),
                    ) {
                        rankedResults.forEachIndexed { index, item ->
                            DnsResolverRow(
                                item = item,
                                isFastest = item.id == fastestId,
                                maxMedianMs = maxMedianMs,
                                shape = dnsTileShape(index = index, count = rankedResults.size),
                            )
                        }
                    }
                }

                // Explanation note
                item(key = "dns_speed_note") {
                    Text(
                        text = stringResource(R.string.dns_speed_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .testTag("dns_speed_explanation"),
                    )
                }

                // How to use a faster DNS note
                item(key = "dns_howto_card") {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dns_howto_card"),
                        shape = RoundedCornerShape(DnsOuterCorner),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.dns_howto_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.dns_howto_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(start = 4.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.dns_howto_cloudflare_row),
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = JetBrainsMono,
                                        fontWeight = FontWeight.Medium,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = stringResource(R.string.dns_howto_google_row),
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = JetBrainsMono,
                                        fontWeight = FontWeight.Medium,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = stringResource(R.string.dns_howto_quad9_row),
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = JetBrainsMono,
                                        fontWeight = FontWeight.Medium,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            Text(
                                text = stringResource(R.string.dns_howto_footer),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                item(key = "dns_bottom_spacer") {
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun DnsActionButton(
    isRunning: Boolean,
    hasCompleted: Boolean,
    onRunClick: () -> Unit,
    onCancelClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor by animateColorAsState(
        targetValue = if (isRunning) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.primary
        },
        label = "dnsBtnContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = if (isRunning) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onPrimary
        },
        label = "dnsBtnContent",
    )

    val label = when {
        isRunning -> stringResource(R.string.action_cancel)
        hasCompleted -> stringResource(R.string.action_run_again)
        else -> stringResource(R.string.action_run)
    }

    val icon = when {
        isRunning -> SpeedTestIcons.Stop
        hasCompleted -> Icons.Rounded.Refresh
        else -> Icons.Rounded.PlayArrow
    }

    Button(
        onClick = if (isRunning) onCancelClick else onRunClick,
        modifier = modifier
            .heightIn(min = 52.dp)
            .testTag(if (isRunning) "dns_cancel_button" else "dns_run_button"),
        shape = RoundedCornerShape(26.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun DnsResolverRow(
    item: DnsResolverResult,
    isFastest: Boolean,
    maxMedianMs: Double,
    shape: Shape,
) {
    val name = resolverDisplayName(item.id)
    val addressLabel = item.serverIp ?: stringResource(R.string.dns_resolver_system_address)
    val formattedMedian = formatDnsMedianMs(item.medianMs)

    val valueText = when (item.status) {
        DnsResolverStatus.COMPLETED -> formattedMedian ?: stringResource(R.string.dns_status_unreachable)
        DnsResolverStatus.UNREACHABLE -> stringResource(R.string.dns_status_unreachable)
        DnsResolverStatus.RUNNING -> stringResource(R.string.dns_status_testing)
        DnsResolverStatus.PENDING -> stringResource(R.string.dns_status_ready)
    }

    val valueColor = when {
        item.status == DnsResolverStatus.UNREACHABLE -> MaterialTheme.colorScheme.error
        isFastest -> MaterialTheme.colorScheme.primary
        item.status == DnsResolverStatus.COMPLETED -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val targetBarFraction = if (
        item.status == DnsResolverStatus.COMPLETED &&
        item.medianMs != null &&
        item.medianMs > 0.0 &&
        maxMedianMs > 0.0
    ) {
        (item.medianMs / maxMedianMs).toFloat().coerceIn(0.08f, 1f)
    } else {
        0f
    }

    val animatedFraction by animateFloatAsState(
        targetValue = targetBarFraction,
        animationSpec = tween(durationMillis = 320),
        label = "dnsBarFraction",
    )

    val barColor = if (isFastest) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.secondary
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("dns_resolver_row_${item.id.name.lowercase()}"),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (isFastest) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.testTag("dns_fastest_badge"),
                            ) {
                                Text(
                                    text = stringResource(R.string.dns_badge_fastest),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = addressLabel,
                        style = if (item.serverIp != null) {
                            MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono)
                        } else {
                            MaterialTheme.typography.bodySmall
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = valueText,
                    style = if (item.status == DnsResolverStatus.COMPLETED && formattedMedian != null) {
                        MaterialTheme.typography.titleSmall.copy(
                            fontFamily = JetBrainsMono,
                            fontWeight = FontWeight.SemiBold,
                        )
                    } else {
                        MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                    },
                    color = valueColor,
                    modifier = Modifier.testTag("dns_resolver_value_${item.id.name.lowercase()}"),
                )
            }

            // Simple horizontal bar per resolver
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                if (animatedFraction > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(animatedFraction)
                            .clip(RoundedCornerShape(50))
                            .background(barColor),
                    )
                }
            }

            if (item.failedQueries > 0 && (item.status == DnsResolverStatus.COMPLETED || item.status == DnsResolverStatus.UNREACHABLE)) {
                val failedText = if (item.failedQueries == 1) {
                    stringResource(R.string.dns_failed_queries_one)
                } else {
                    stringResource(R.string.dns_failed_queries_many, item.failedQueries)
                }
                Text(
                    text = failedText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (item.id == DnsResolverId.SYSTEM) {
                Text(
                    text = stringResource(R.string.dns_resolver_system_cache_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun resolverDisplayName(id: DnsResolverId): String = when (id) {
    DnsResolverId.SYSTEM -> stringResource(R.string.dns_resolver_system_name)
    DnsResolverId.CLOUDFLARE -> stringResource(R.string.dns_resolver_cloudflare_name)
    DnsResolverId.GOOGLE -> stringResource(R.string.dns_resolver_google_name)
    DnsResolverId.QUAD9 -> stringResource(R.string.dns_resolver_quad9_name)
}
