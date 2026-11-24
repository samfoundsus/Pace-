package com.example.viewmodel

import android.Manifest
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppContainer
import com.example.data.diagnostics.CrashReportStore
import com.example.data.settings.UserSettings
import com.example.engine.speedtest.NetworkMonitor
import com.example.engine.tools.InternetInfoClient
import com.example.ui.screens.SpeedTestApp
import com.example.ui.theme.SpeedtestTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NetworkInfoAndCrashReportTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var appContext: Application

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        CrashReportStore(appContext).deleteReport()
        configureWifiConnectivity(appContext)
    }

    @After
    fun tearDown() {
        CrashReportStore(appContext).deleteReport()
        Settings.Global.putFloat(
            appContext.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
    }

    @Test
    fun networkInfoViewModel_disposingWhileLookupsPendingDoesNotThrow() = runBlocking {
        val lookupStarted = CountDownLatch(1)
        val slowClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                lookupStarted.countDown()
                val deadline = System.currentTimeMillis() + 3_000L
                while (System.currentTimeMillis() < deadline) {
                    if (chain.call().isCanceled()) {
                        throw IOException("Canceled")
                    }
                    try {
                        Thread.sleep(20L)
                    } catch (_: InterruptedException) {
                        throw IOException("Interrupted")
                    }
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"success":true,"ip":"203.0.113.10","connection":{"isp":"TestISP"}}"""
                        .toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val vm = NetworkInfoViewModel(
            networkMonitor = NetworkMonitor(appContext),
            internetInfoClient = InternetInfoClient(slowClient),
        )

        assertTrue("Expected pending lookup to start", lookupStarted.await(2, TimeUnit.SECONDS))
        assertTrue(vm.uiState.value.isLoadingInternetInfo)

        // Clearing while lookups are pending must not throw on the calling thread.
        vm.clearForTest()
        delay(150L)

        // State must NOT have been populated with late results after clear.
        assertNull(vm.uiState.value.publicIp)
        assertNull(vm.uiState.value.isp)
        assertFalse(vm.uiState.value.hasLoadedInternetInfo)
    }

    @Test
    fun networkInfoViewModel_clearingTwiceAndUnregisteringTwiceIsSafe() {
        val monitor = NetworkMonitor(appContext)
        // Double start and double stop must be safe and idempotent.
        monitor.startObserving()
        monitor.startObserving()
        monitor.stopObserving()
        monitor.stopObserving()

        val fastClient = createFastMockInternetClient()
        val vm = NetworkInfoViewModel(
            networkMonitor = NetworkMonitor(appContext),
            internetInfoClient = InternetInfoClient(fastClient),
        )

        // Clearing the ViewModel twice (and calling methods afterward) must never throw.
        vm.clearForTest()
        vm.clearForTest()
        vm.onRefreshAll()
        vm.onResumeRefresh()
        vm.onWifiPermissionResult(granted = true, permanentlyDenied = false)
        vm.onPhonePermissionResult(granted = true)
    }

    @Test
    fun networkInfoViewModel_cancelledLookupDoesNotUpdateState() = runBlocking {
        val requestStarted = CountDownLatch(1)
        val allowFinish = CountDownLatch(1)
        val slowClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestStarted.countDown()
                allowFinish.await(2, TimeUnit.SECONDS)
                if (chain.call().isCanceled()) {
                    throw IOException("Canceled")
                }
                val url = chain.request().url.toString()
                val bodyText = when {
                    url.contains("ipwho") -> """{"success":true,"ip":"198.51.100.42","connection":{"isp":"ShouldNotAppear"}}"""
                    url.contains("api64") -> "2001:db8::1"
                    else -> "198.51.100.42"
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(bodyText.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val vm = NetworkInfoViewModel(
            networkMonitor = NetworkMonitor(appContext),
            internetInfoClient = InternetInfoClient(slowClient),
        )

        assertTrue(requestStarted.await(2, TimeUnit.SECONDS))

        // Cancel the in-flight lookup before it finishes.
        vm.cancelPendingInternetLookup()
        allowFinish.countDown()
        delay(200L)

        // Cancelled lookup must not mark hasLoadedInternetInfo = true or write IP/ISP/IPv4/IPv6.
        val state = vm.uiState.value
        assertFalse(state.hasLoadedInternetInfo)
        assertNull(state.publicIp)
        assertNull(state.isp)
        assertNull(state.ipv4Supported)
        assertNull(state.ipv6Supported)

        vm.clearForTest()
    }

    @Test
    fun crashReportStore_writesBoundedLatestReportAndChainsDefaultHandler() {
        val store = CrashReportStore(appContext)
        assertFalse(store.hasCrashReport.value)
        assertNull(store.readReport())

        val delegateInvoked = AtomicBoolean(false)
        val handler = CrashReportStore.createUncaughtExceptionHandler(
            context = appContext,
            delegate = Thread.UncaughtExceptionHandler { _, _ ->
                delegateInvoked.set(true)
            },
        )

        val boom = IllegalStateException("Synthetic test crash #1")
        handler.uncaughtException(Thread("worker-crash-test"), boom)

        assertTrue("Default uncaught exception handler must still be invoked", delegateInvoked.get())
        assertTrue(store.refresh())

        val report = store.readReport()
        assertNotNull(report)
        assertTrue(report!!.contains("worker-crash-test"))
        assertTrue(report.contains("IllegalStateException: Synthetic test crash #1"))
        assertTrue(report.contains("App version:"))
        assertTrue(report.contains("Android:"))
        assertTrue(report.contains("Device:"))

        // Writing a huge report replaces the previous one and strictly caps at 20 KB.
        val hugeMessage = "X".repeat(40_000)
        store.writeReport("main", RuntimeException(hugeMessage))
        val boundedReport = store.readReport()!!
        val byteSize = boundedReport.toByteArray(Charsets.UTF_8).size
        assertTrue("Crash report must be <= 20 KB, was $byteSize bytes", byteSize <= CrashReportStore.MAX_REPORT_BYTES)
        assertFalse("Only the latest report should be retained", boundedReport.contains("Synthetic test crash #1"))
        assertTrue(boundedReport.contains("truncated to 20 KB"))

        // Deleting removes the report cleanly.
        store.deleteReport()
        assertFalse(store.hasCrashReport.value)
        assertNull(store.readReport())
    }

    @Test
    fun toolsNavigationWalkthrough_networkInfoDnsExtendedAndCrashReportBackNavigation() {
        val container = AppContainer.getInstance(appContext)
        val settingsViewModel = SettingsViewModel(
            settingsRepository = container.settingsRepository,
            serverRepository = container.serverRepository,
            crashReportStore = container.crashReportStore,
        )

        composeTestRule.setContent {
            SpeedtestTheme(darkTheme = false, dynamicColor = false) {
                SpeedTestApp(
                    userSettings = UserSettings(),
                    settingsViewModel = settingsViewModel,
                )
            }
        }
        composeTestRule.waitForIdle()

        // 1. Navigate to Tools tab
        composeTestRule.onNodeWithTag("nav_tab_tools").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("tools_screen").assertIsDisplayed()

        // 2. Tools > Network info > quick Back (without permissions)
        composeTestRule.onNodeWithTag("tools_tile_network_info").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("network_info_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("network_info_back_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("tools_screen").assertIsDisplayed()

        // 3. Tools > Network info > wait for lookups/settle > Back (without permissions)
        composeTestRule.onNodeWithTag("tools_tile_network_info").performClick()
        composeTestRule.mainClock.advanceTimeBy(600L)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("network_info_back_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("tools_screen").assertIsDisplayed()

        // 4. Grant Wi-Fi location & phone state permissions, then walk Tools > Network info > Back twice
        Shadows.shadowOf(appContext).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,
        )
        repeat(2) {
            composeTestRule.onNodeWithTag("tools_tile_network_info").performClick()
            composeTestRule.mainClock.advanceTimeBy(400L)
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("network_info_screen").assertIsDisplayed()
            composeTestRule.onNodeWithTag("network_info_back_button").performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("tools_screen").assertIsDisplayed()
        }

        // 5. Tools > DNS benchmark > Back
        composeTestRule.onNodeWithTag("tools_tile_dns_benchmark").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("dns_benchmark_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("dns_benchmark_back_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("tools_screen").assertIsDisplayed()

        // 6. Tools > Extended test > Back
        composeTestRule.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasTestTag("tools_tile_extended_test"))
        composeTestRule.onNodeWithTag("tools_tile_extended_test").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("extended_test_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("extended_test_back_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("tools_screen").assertIsDisplayed()

        // 7. Settings > Diagnostics hidden when no crash report exists
        composeTestRule.onNodeWithTag("nav_tab_settings").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_screen").assertIsDisplayed()
        assertEquals(
            0,
            composeTestRule.onAllNodesWithTag("settings_last_crash_report_row").fetchSemanticsNodes().size,
        )

        // 8. Save a local crash report -> Diagnostics row appears -> open -> Delete -> Back -> row gone
        container.crashReportStore.writeReport("main", IllegalArgumentException("Walkthrough test crash"))
        composeTestRule.waitForIdle()
        composeTestRule.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasTestTag("settings_last_crash_report_row"))
        composeTestRule.onNodeWithTag("settings_last_crash_report_row").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("crash_report_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("crash_report_body").assertIsDisplayed()
        composeTestRule.onNodeWithTag("crash_report_delete_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_screen").assertIsDisplayed()
        assertEquals(
            0,
            composeTestRule.onAllNodesWithTag("settings_last_crash_report_row").fetchSemanticsNodes().size,
        )

        // 9. Switch back to Test tab cleanly (verifying tab fade-through + live screen state)
        composeTestRule.onNodeWithTag("nav_tab_test").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("nav_tab_test").assertIsDisplayed()
    }

    private fun createFastMockInternetClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val url = chain.request().url.toString()
            val bodyText = when {
                url.contains("ipwho") -> """{"success":true,"ip":"203.0.113.5","connection":{"isp":"FastNet"}}"""
                url.contains("api64") -> "2001:db8::5"
                else -> "203.0.113.5"
            }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(bodyText.toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

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
