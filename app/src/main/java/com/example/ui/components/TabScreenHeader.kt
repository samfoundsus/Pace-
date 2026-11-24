package com.example.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Shared top-level tab header constants used by History, Tools, and Settings so that
 * titles sit at the exact same position on every device, font scale, and theme.
 */
object TabScreenHeaderDefaults {
    /** History's original 16dp top offset plus 20dp so the title sits comfortably below the status bar. */
    val TopOffset = 36.dp
    val BottomSpacing = 12.dp
    val HorizontalPadding = 20.dp
    val MinRowHeight = 48.dp
    val ActionSpacing = 8.dp

    /** Top content padding for the scrollable list directly below [TabScreenHeader]. */
    val ContentTopGap = 4.dp
}

/**
 * Shared header composable for the History, Tools, and Settings tabs.
 * Renders the screen title on the left and an optional vertically aligned action slot on the right
 * (for example, "Clear all" on History).
 *
 * The header height is determined strictly by the title and [TabScreenHeaderDefaults.MinRowHeight]
 * so the presence or height of an optional action never shifts the title vertically.
 */
@Composable
fun TabScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    titleTestTag: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val density = LocalDensity.current
    val gapPx = with(density) { TabScreenHeaderDefaults.ActionSpacing.roundToPx() }
    val minRowHeightPx = with(density) { TabScreenHeaderDefaults.MinRowHeight.roundToPx() }

    Layout(
        content = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (titleTestTag != null) Modifier.testTag(titleTestTag) else Modifier,
            )
            if (action != null) {
                Box(contentAlignment = Alignment.CenterEnd) {
                    action()
                }
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(
                start = TabScreenHeaderDefaults.HorizontalPadding,
                end = TabScreenHeaderDefaults.HorizontalPadding,
                top = TabScreenHeaderDefaults.TopOffset,
                bottom = TabScreenHeaderDefaults.BottomSpacing,
            ),
    ) { measurables, constraints ->
        val looseConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val actionPlaceable = measurables.getOrNull(1)?.measure(looseConstraints)

        val maxTitleWidth = if (actionPlaceable != null && constraints.hasBoundedWidth) {
            (constraints.maxWidth - actionPlaceable.width - gapPx).coerceAtLeast(0)
        } else {
            constraints.maxWidth
        }
        val titlePlaceable = measurables[0].measure(
            looseConstraints.copy(maxWidth = maxTitleWidth),
        )

        val layoutWidth = if (constraints.hasBoundedWidth) {
            constraints.maxWidth
        } else {
            titlePlaceable.width + (actionPlaceable?.let { it.width + gapPx } ?: 0)
        }
        val layoutHeight = maxOf(titlePlaceable.height, minRowHeightPx)
            .coerceIn(constraints.minHeight, constraints.maxHeight)

        layout(layoutWidth, layoutHeight) {
            val titleY = (layoutHeight - titlePlaceable.height) / 2
            titlePlaceable.placeRelative(x = 0, y = titleY)

            if (actionPlaceable != null) {
                val actionX = (layoutWidth - actionPlaceable.width).coerceAtLeast(0)
                val actionY = (layoutHeight - actionPlaceable.height) / 2
                actionPlaceable.placeRelative(x = actionX, y = actionY)
            }
        }
    }
}
