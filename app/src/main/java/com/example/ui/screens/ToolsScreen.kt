package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.components.TabScreenHeader
import com.example.ui.components.TabScreenHeaderDefaults

private val ToolsGroupOuterCorner = 28.dp
private val ToolsGroupInnerCorner = 4.dp
private val ToolsGroupGap = 2.dp

private fun toolsTileShape(index: Int, count: Int): Shape = when {
    count <= 1 -> RoundedCornerShape(ToolsGroupOuterCorner)
    index == 0 -> RoundedCornerShape(
        topStart = ToolsGroupOuterCorner,
        topEnd = ToolsGroupOuterCorner,
        bottomStart = ToolsGroupInnerCorner,
        bottomEnd = ToolsGroupInnerCorner,
    )
    index == count - 1 -> RoundedCornerShape(
        topStart = ToolsGroupInnerCorner,
        topEnd = ToolsGroupInnerCorner,
        bottomStart = ToolsGroupOuterCorner,
        bottomEnd = ToolsGroupOuterCorner,
    )
    else -> RoundedCornerShape(ToolsGroupInnerCorner)
}

@Composable
fun ToolsScreen(
    onOpenNetworkInfo: () -> Unit,
    onOpenDnsBenchmark: () -> Unit,
    onOpenExtendedTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("tools_screen"),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp),
        ) {
            TabScreenHeader(
                title = stringResource(R.string.tools_title),
                titleTestTag = "tools_screen_title",
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = TabScreenHeaderDefaults.ContentTopGap,
                    bottom = 20.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                item(key = "tools_diagnostics") {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(R.string.tools_section_diagnostics),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 20.dp, bottom = 10.dp),
                        )
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(ToolsGroupGap),
                        ) {
                            ToolsNavigationTile(
                                title = stringResource(R.string.tools_network_info_title),
                                subtitle = stringResource(R.string.tools_network_info_subtitle),
                                shape = toolsTileShape(index = 0, count = 3),
                                onClick = onOpenNetworkInfo,
                                testTag = "tools_tile_network_info",
                            )
                            ToolsNavigationTile(
                                title = stringResource(R.string.tools_dns_benchmark_title),
                                subtitle = stringResource(R.string.tools_dns_benchmark_subtitle),
                                shape = toolsTileShape(index = 1, count = 3),
                                onClick = onOpenDnsBenchmark,
                                testTag = "tools_tile_dns_benchmark",
                            )
                            ToolsNavigationTile(
                                title = stringResource(R.string.tools_extended_test_title),
                                subtitle = stringResource(R.string.tools_extended_test_subtitle),
                                shape = toolsTileShape(index = 2, count = 3),
                                onClick = onOpenExtendedTest,
                                testTag = "tools_tile_extended_test",
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolsNavigationTile(
    title: String,
    subtitle: String,
    shape: Shape,
    onClick: () -> Unit,
    testTag: String,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(testTag),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 76.dp)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
