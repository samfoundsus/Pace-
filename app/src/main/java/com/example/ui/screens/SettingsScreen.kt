package com.example.ui.screens

import android.os.Build
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.settings.SpeedUnit
import com.example.data.settings.ThemeMode
import com.example.data.settings.UserSettings

private val GroupOuterCorner = 28.dp
private val GroupInnerCorner = 4.dp
private val GroupTileGap = 2.dp

private fun groupTileShape(index: Int, count: Int): Shape = when {
    count <= 1 -> RoundedCornerShape(GroupOuterCorner)
    index == 0 -> RoundedCornerShape(
        topStart = GroupOuterCorner,
        topEnd = GroupOuterCorner,
        bottomStart = GroupInnerCorner,
        bottomEnd = GroupInnerCorner,
    )
    index == count - 1 -> RoundedCornerShape(
        topStart = GroupInnerCorner,
        topEnd = GroupInnerCorner,
        bottomStart = GroupOuterCorner,
        bottomEnd = GroupOuterCorner,
    )
    else -> RoundedCornerShape(GroupInnerCorner)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: UserSettings,
    onThemeModeChange: (ThemeMode) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onSpeedUnitChange: (SpeedUnit) -> Unit,
    onLiteModeChange: (Boolean) -> Unit,
    onHapticFeedbackChange: (Boolean) -> Unit,
    onMobileDataWarningChange: (Boolean) -> Unit,
    onIncludeCommunityServersChange: (Boolean) -> Unit = {},
    hasCrashReport: Boolean = false,
    onOpenCrashReport: () -> Unit = {},
    onRefreshCrashReport: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) {
        onRefreshCrashReport()
    }

    val supportsDynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    var showThemeDialog by remember { mutableStateOf(false) }
    var showUnitsDialog by remember { mutableStateOf(false) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        rememberTopAppBarState(),
    )

    if (showThemeDialog) {
        RadioChoiceDialog(
            title = stringResource(R.string.settings_theme_dialog_title),
            options = listOf(
                ThemeMode.SYSTEM to stringResource(R.string.settings_theme_system),
                ThemeMode.LIGHT to stringResource(R.string.settings_theme_light),
                ThemeMode.DARK to stringResource(R.string.settings_theme_dark),
            ),
            selected = settings.themeMode,
            onSelect = { mode ->
                onThemeModeChange(mode)
                showThemeDialog = false
            },
            onDismiss = { showThemeDialog = false },
            tagPrefix = "theme_option",
        )
    }

    if (showUnitsDialog) {
        RadioChoiceDialog(
            title = stringResource(R.string.settings_units_dialog_title),
            options = listOf(
                SpeedUnit.MBPS to stringResource(R.string.unit_mbps),
                SpeedUnit.MB_PER_SEC to stringResource(R.string.unit_mb_per_sec),
            ),
            selected = settings.speedUnit,
            onSelect = { unit ->
                onSpeedUnitChange(unit)
                showUnitsDialog = false
            },
            onDismiss = { showUnitsDialog = false },
            tagPrefix = "unit_option",
        )
    }

    val themeSummary = when (settings.themeMode) {
        ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
        ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
        ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
    }

    val unitSummary = when (settings.speedUnit) {
        SpeedUnit.MBPS -> stringResource(R.string.unit_mbps)
        SpeedUnit.MB_PER_SEC -> stringResource(R.string.unit_mb_per_sec)
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .testTag("settings_screen"),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                scrollBehavior = scrollBehavior,
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
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
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                item(key = "section_appearance") {
                    SettingsSection(title = stringResource(R.string.settings_appearance_section)) {
                        SettingsChoiceTile(
                            title = stringResource(R.string.settings_theme_label),
                            summary = themeSummary,
                            shape = groupTileShape(index = 0, count = 2),
                            onClick = { showThemeDialog = true },
                            testTag = "settings_theme_row",
                        )

                        SettingsSwitchTile(
                            title = stringResource(R.string.settings_dynamic_color_title),
                            summary = if (supportsDynamicColor) {
                                stringResource(R.string.settings_dynamic_color_summary)
                            } else {
                                stringResource(R.string.settings_dynamic_color_unsupported)
                            },
                            checked = supportsDynamicColor && settings.dynamicColor,
                            enabled = supportsDynamicColor,
                            shape = groupTileShape(index = 1, count = 2),
                            onCheckedChange = onDynamicColorChange,
                            testTag = "switch_dynamic_color",
                        )
                    }
                }

                item(key = "section_test") {
                    SettingsSection(title = stringResource(R.string.settings_test_section)) {
                        SettingsChoiceTile(
                            title = stringResource(R.string.settings_units_label),
                            summary = unitSummary,
                            shape = groupTileShape(index = 0, count = 4),
                            onClick = { showUnitsDialog = true },
                            testTag = "settings_units_row",
                        )

                        SettingsSwitchTile(
                            title = stringResource(R.string.settings_lite_mode_title),
                            summary = stringResource(R.string.settings_lite_mode_subtitle),
                            checked = settings.liteMode,
                            enabled = true,
                            shape = groupTileShape(index = 1, count = 4),
                            onCheckedChange = onLiteModeChange,
                            testTag = "switch_lite_mode",
                        )

                        SettingsSwitchTile(
                            title = stringResource(R.string.settings_haptics_title),
                            summary = stringResource(R.string.settings_haptics_subtitle),
                            checked = settings.hapticFeedback,
                            enabled = true,
                            shape = groupTileShape(index = 2, count = 4),
                            onCheckedChange = onHapticFeedbackChange,
                            testTag = "switch_haptic_feedback",
                        )

                        SettingsSwitchTile(
                            title = stringResource(R.string.settings_community_servers_title),
                            summary = stringResource(R.string.settings_community_servers_subtitle),
                            checked = settings.includeCommunityServers,
                            enabled = true,
                            shape = groupTileShape(index = 3, count = 4),
                            onCheckedChange = onIncludeCommunityServersChange,
                            testTag = "switch_include_community_servers",
                        )
                    }
                }

                item(key = "section_data") {
                    SettingsSection(title = stringResource(R.string.settings_data_section)) {
                        SettingsSwitchTile(
                            title = stringResource(R.string.settings_mobile_warning_title),
                            summary = stringResource(R.string.settings_mobile_warning_subtitle),
                            checked = settings.mobileDataWarning,
                            enabled = true,
                            shape = groupTileShape(index = 0, count = 1),
                            onCheckedChange = onMobileDataWarningChange,
                            testTag = "switch_mobile_data_warning",
                        )
                    }
                }

                item(key = "section_privacy") {
                    SettingsSection(title = stringResource(R.string.settings_privacy_section)) {
                        SettingsInfoTile(
                            title = stringResource(R.string.settings_privacy_title),
                            body = stringResource(R.string.settings_privacy_body),
                            shape = groupTileShape(index = 0, count = 1),
                            testTag = "settings_privacy_tile",
                        )
                    }
                }

                if (hasCrashReport) {
                    item(key = "section_diagnostics") {
                        SettingsSection(title = stringResource(R.string.settings_diagnostics_section)) {
                            SettingsChoiceTile(
                                title = stringResource(R.string.settings_last_crash_report_title),
                                summary = stringResource(R.string.settings_last_crash_report_summary),
                                shape = groupTileShape(index = 0, count = 1),
                                onClick = onOpenCrashReport,
                                showChevron = true,
                                testTag = "settings_last_crash_report_row",
                            )
                        }
                    }
                }

                item(key = "brand_footer") {
                    Text(
                        text = stringResource(R.string.settings_footer_brand),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 20.dp)
                            .testTag("settings_brand_footer"),
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 20.dp, bottom = 10.dp),
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(GroupTileGap),
        ) {
            content()
        }
    }
}

@Composable
private fun SettingsInfoTile(
    title: String,
    body: String,
    shape: Shape,
    testTag: String,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsChoiceTile(
    title: String,
    summary: String,
    shape: Shape,
    onClick: () -> Unit,
    showChevron: Boolean = false,
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
                .heightIn(min = 72.dp)
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
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showChevron) {
                Spacer(modifier = Modifier.width(12.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsSwitchTile(
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean,
    shape: Shape,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
) {
    val contentAlpha = if (enabled) 1f else 0.52f

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(
                enabled = enabled,
                role = Role.Switch,
                onClick = { onCheckedChange(!checked) },
            ),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .alpha(contentAlpha),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                thumbContent = if (checked) {
                    {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            modifier = Modifier.size(SwitchDefaults.IconSize),
                        )
                    }
                } else {
                    null
                },
                modifier = Modifier.testTag(testTag),
            )
        }
    }
}

@Composable
private fun <T> RadioChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    tagPrefix: String,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                options.forEach { (value, label) ->
                    val isSelected = value == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(
                                role = Role.RadioButton,
                                onClick = { onSelect(value) },
                            )
                            .heightIn(min = 52.dp)
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .testTag("${tagPrefix}_${value.toString().lowercase()}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = null,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.action_cancel))
            }
        },
    )
}
