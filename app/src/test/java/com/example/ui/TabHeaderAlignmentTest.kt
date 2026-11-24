package com.example.ui

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppContainer
import com.example.data.models.ConnectionType
import com.example.data.models.TestResult
import com.example.data.settings.SpeedUnit
import com.example.data.settings.UserSettings
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SpeedTestApp
import com.example.ui.screens.ToolsScreen
import com.example.ui.theme.SpeedtestTheme
import com.example.viewmodel.SettingsViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TabHeaderAlignmentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var appContext: Application

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        configureWifiConnectivity(appContext)
    }

    @Test
    fun appTabNavigation_historyToolsAndSettingsHaveIdenticalTitlePositionInLightTheme() {
        verifyAppTabTitleAlignment(darkTheme = false, fontScale = 1.0f)
    }

    @Test
    fun appTabNavigation_historyToolsAndSettingsHaveIdenticalTitlePositionInDarkTheme() {
        verifyAppTabTitleAlignment(darkTheme = true, fontScale = 1.0f)
    }

    @Test
    fun appTabNavigation_historyToolsAndSettingsHaveIdenticalTitlePositionWithLargeFont() {
        verifyAppTabTitleAlignment(darkTheme = false, fontScale = 1.6f)
    }

    @Test
    fun historyWithClearAll_matchesToolsAndSettingsTitlePositionAndAlignsActionButton() {
        verifyHistoryWithClearAllAlignment(fontScale = 1.0f)
    }

    @Test
    fun historyWithClearAll_matchesToolsAndSettingsTitlePositionWithLargeFont() {
        verifyHistoryWithClearAllAlignment(fontScale = 1.6f)
    }

    private fun verifyHistoryWithClearAllAlignment(fontScale: Float) {
        val sampleResult = TestResult(
            id = 1L,
            timestamp = 1_700_000_000_000L,
            download = 120.5,
            upload = 42.3,
            ping = 14.2,
            jitter = 2.1,
            connectionType = ConnectionType.WIFI,
        )

        var activeScreen by mutableIntStateOf(0)
        composeTestRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = baseDensity.density,
                    fontScale = fontScale,
                ),
            ) {
                SpeedtestTheme(darkTheme = false, dynamicColor = false) {
                    when (activeScreen) {
                        0 -> HistoryScreen(
                            results = listOf(sampleResult),
                            speedUnit = SpeedUnit.MBPS,
                            onResultClick = {},
                            onDeleteResult = {},
                            onClearAll = {},
                        )
                        1 -> ToolsScreen(
                            onOpenNetworkInfo = {},
                            onOpenDnsBenchmark = {},
                            onOpenExtendedTest = {},
                        )
                        else -> SettingsScreen(
                            settings = UserSettings(),
                            onThemeModeChange = {},
                            onDynamicColorChange = {},
                            onSpeedUnitChange = {},
                            onLiteModeChange = {},
                            onHapticFeedbackChange = {},
                            onMobileDataWarningChange = {},
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()

        val historyTitleBounds = composeTestRule.onNodeWithTag("history_screen_title")
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        val clearAllBounds = composeTestRule.onNodeWithTag("clear_all_history_button")
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()

        val historyTitleCenterY = (historyTitleBounds.top.value + historyTitleBounds.bottom.value) / 2f
        val clearAllCenterY = (clearAllBounds.top.value + clearAllBounds.bottom.value) / 2f
        assertTrue(
            "Expected Clear all button (centerY=$clearAllCenterY) to align with History title (centerY=$historyTitleCenterY) at fontScale=$fontScale",
            abs(historyTitleCenterY - clearAllCenterY) <= 1.5f,
        )

        composeTestRule.runOnIdle { activeScreen = 1 }
        composeTestRule.waitForIdle()
        val toolsTitleBounds = composeTestRule.onNodeWithTag("tools_screen_title")
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()

        composeTestRule.runOnIdle { activeScreen = 2 }
        composeTestRule.waitForIdle()
        val settingsTitleBounds = composeTestRule.onNodeWithTag("settings_screen_title")
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()

        assertEquals(historyTitleBounds.top.value, toolsTitleBounds.top.value, 0.5f)
        assertEquals(historyTitleBounds.left.value, toolsTitleBounds.left.value, 0.5f)
        assertEquals(historyTitleBounds.top.value, settingsTitleBounds.top.value, 0.5f)
        assertEquals(historyTitleBounds.left.value, settingsTitleBounds.left.value, 0.5f)
    }

    private fun verifyAppTabTitleAlignment(darkTheme: Boolean, fontScale: Float) {
        val container = AppContainer.getInstance(appContext)
        val settingsViewModel = SettingsViewModel(
            settingsRepository = container.settingsRepository,
            serverRepository = container.serverRepository,
            crashReportStore = container.crashReportStore,
        )

        composeTestRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = baseDensity.density,
                    fontScale = fontScale,
                ),
            ) {
                SpeedtestTheme(darkTheme = darkTheme, dynamicColor = false) {
                    SpeedTestApp(
                        userSettings = UserSettings(),
                        settingsViewModel = settingsViewModel,
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        // 1. Switch to History and measure title bounds
        composeTestRule.onNodeWithTag("nav_tab_history").performClick()
        composeTestRule.waitForIdle()
        val historyBounds = composeTestRule.onNodeWithTag("history_screen_title")
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()

        // 2. Switch to Tools (through fade-through transition) and verify title bounds
        composeTestRule.onNodeWithTag("nav_tab_tools").performClick()
        composeTestRule.mainClock.advanceTimeBy(150L)
        composeTestRule.waitForIdle()
        val toolsBounds = composeTestRule.onNodeWithTag("tools_screen_title")
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()

        // 3. Switch to Settings (through fade-through transition) and verify title bounds
        composeTestRule.onNodeWithTag("nav_tab_settings").performClick()
        composeTestRule.mainClock.advanceTimeBy(150L)
        composeTestRule.waitForIdle()
        val settingsBounds = composeTestRule.onNodeWithTag("settings_screen_title")
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()

        assertEquals(
            "History and Tools titles must have identical top position (fontScale=$fontScale, dark=$darkTheme)",
            historyBounds.top.value,
            toolsBounds.top.value,
            0.5f,
        )
        assertEquals(
            "History and Tools titles must have identical left position (fontScale=$fontScale, dark=$darkTheme)",
            historyBounds.left.value,
            toolsBounds.left.value,
            0.5f,
        )
        assertEquals(
            "History and Settings titles must have identical top position (fontScale=$fontScale, dark=$darkTheme)",
            historyBounds.top.value,
            settingsBounds.top.value,
            0.5f,
        )
        assertEquals(
            "History and Settings titles must have identical left position (fontScale=$fontScale, dark=$darkTheme)",
            historyBounds.left.value,
            settingsBounds.left.value,
            0.5f,
        )
    }

    private fun configureWifiConnectivity(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadowCm = Shadows.shadowOf(cm)
        val caps = ShadowNetworkCapabilities.newInstance()
        Shadows.shadowOf(caps).apply {
            addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        shadowCm.setNetworkCapabilities(cm.activeNetwork, caps)
    }
}
