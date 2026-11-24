package com.example.ui.screens

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.annotation.StringRes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.R
import com.example.data.models.TestResult
import com.example.data.settings.UserSettings
import com.example.ui.components.SpeedTestIcons
import com.example.viewmodel.DnsBenchmarkViewModel
import com.example.viewmodel.ExtendedTestViewModel
import com.example.viewmodel.HapticCue
import com.example.viewmodel.HistoryViewModel
import com.example.viewmodel.NetworkInfoViewModel
import com.example.viewmodel.SettingsViewModel
import com.example.viewmodel.SnackbarNotice
import com.example.viewmodel.SpeedTestUiState
import com.example.viewmodel.SpeedTestViewModel

object SpeedTestRoutes {
    const val TEST = "test"
    const val HISTORY = "history"
    const val HISTORY_DETAIL_ARG = "resultId"
    const val HISTORY_DETAIL = "history_detail/{$HISTORY_DETAIL_ARG}"
    const val TOOLS = "tools"
    const val NETWORK_INFO = "tools/network_info"
    const val DNS_BENCHMARK = "tools/dns_benchmark"
    const val EXTENDED_TEST = "tools/extended_test"
    const val SETTINGS = "settings"

    fun historyDetail(resultId: Long): String = "history_detail/$resultId"
}

private data class SpeedTestDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val testTag: String,
)

@Composable
fun SpeedTestApp(
    userSettings: UserSettings,
    settingsViewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current

    val speedTestViewModel: SpeedTestViewModel = viewModel(
        factory = SpeedTestViewModel.factory(context),
    )
    val historyViewModel: HistoryViewModel = viewModel(
        factory = HistoryViewModel.factory(context),
    )

    val testUiState by speedTestViewModel.uiState.collectAsStateWithLifecycle()
    val historyItems by historyViewModel.historyItems.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(speedTestViewModel, view) {
        speedTestViewModel.hapticEvents.collect { cue ->
            val feedbackConstant = when (cue) {
                HapticCue.TEST_STARTED -> HapticFeedbackConstants.VIRTUAL_KEY
                HapticCue.TEST_FINISHED -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    HapticFeedbackConstants.CONFIRM
                } else {
                    HapticFeedbackConstants.LONG_PRESS
                }
            }
            view.performHapticFeedback(feedbackConstant)
        }
    }

    LaunchedEffect(speedTestViewModel, context, snackbarHostState) {
        speedTestViewModel.snackbarNotices.collect { notice ->
            val message = when (notice) {
                is SnackbarNotice.SavedServerUnreachable -> context.getString(
                    R.string.snackbar_saved_server_unreachable,
                    notice.serverName,
                )
                is SnackbarNotice.ServerSwitchedToCloudflare -> context.getString(
                    R.string.snackbar_server_fallback_cloudflare,
                    notice.previousServerName,
                )
            }
            snackbarHostState.showSnackbar(message = message)
        }
    }

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: SpeedTestRoutes.TEST
    val isDetailScreen = currentRoute == SpeedTestRoutes.HISTORY_DETAIL ||
        currentRoute == SpeedTestRoutes.NETWORK_INFO ||
        currentRoute == SpeedTestRoutes.DNS_BENCHMARK ||
        currentRoute == SpeedTestRoutes.EXTENDED_TEST
    val selectedTopRoute = when (currentRoute) {
        SpeedTestRoutes.HISTORY_DETAIL -> SpeedTestRoutes.HISTORY
        SpeedTestRoutes.NETWORK_INFO,
        SpeedTestRoutes.DNS_BENCHMARK,
        SpeedTestRoutes.EXTENDED_TEST,
        -> SpeedTestRoutes.TOOLS
        else -> currentRoute
    }

    val destinations = remember {
        listOf(
            SpeedTestDestination(
                route = SpeedTestRoutes.TEST,
                labelRes = R.string.nav_test,
                icon = SpeedTestIcons.Gauge,
                testTag = "nav_tab_test",
            ),
            SpeedTestDestination(
                route = SpeedTestRoutes.HISTORY,
                labelRes = R.string.nav_history,
                icon = SpeedTestIcons.History,
                testTag = "nav_tab_history",
            ),
            SpeedTestDestination(
                route = SpeedTestRoutes.TOOLS,
                labelRes = R.string.nav_tools,
                icon = SpeedTestIcons.Tools,
                testTag = "nav_tab_tools",
            ),
            SpeedTestDestination(
                route = SpeedTestRoutes.SETTINGS,
                labelRes = R.string.nav_settings,
                icon = Icons.Rounded.Settings,
                testTag = "nav_tab_settings",
            ),
        )
    }

    val navigateToTab: (String) -> Unit = { route ->
        if (currentRoute != route) {
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 840.dp

        if (useNavigationRail) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets.safeDrawing,
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxHeight(),
                    ) {
                        Spacer(modifier = Modifier.weight(1f))
                        destinations.forEach { destination ->
                            val label = stringResource(destination.labelRes)
                            NavigationRailItem(
                                selected = selectedTopRoute == destination.route,
                                onClick = { navigateToTab(destination.route) },
                                icon = {
                                    Icon(
                                        imageVector = destination.icon,
                                        contentDescription = label,
                                    )
                                },
                                label = { Text(text = label) },
                                modifier = Modifier.testTag(destination.testTag),
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                    }

                    SpeedTestNavHost(
                        navController = navController,
                        speedTestViewModel = speedTestViewModel,
                        historyViewModel = historyViewModel,
                        settingsViewModel = settingsViewModel,
                        testUiState = testUiState,
                        historyItems = historyItems,
                        userSettings = userSettings,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        } else {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets.safeDrawing,
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                bottomBar = {
                    if (!isDetailScreen) {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ) {
                            destinations.forEach { destination ->
                                val label = stringResource(destination.labelRes)
                                NavigationBarItem(
                                    selected = selectedTopRoute == destination.route,
                                    onClick = { navigateToTab(destination.route) },
                                    icon = {
                                        Icon(
                                            imageVector = destination.icon,
                                            contentDescription = label,
                                        )
                                    },
                                    label = { Text(text = label) },
                                    modifier = Modifier.testTag(destination.testTag),
                                )
                            }
                        }
                    }
                },
            ) { innerPadding ->
                SpeedTestNavHost(
                    navController = navController,
                    speedTestViewModel = speedTestViewModel,
                    historyViewModel = historyViewModel,
                    settingsViewModel = settingsViewModel,
                    testUiState = testUiState,
                    historyItems = historyItems,
                    userSettings = userSettings,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                )
            }
        }
    }
}

@Composable
private fun SpeedTestNavHost(
    navController: NavHostController,
    speedTestViewModel: SpeedTestViewModel,
    historyViewModel: HistoryViewModel,
    settingsViewModel: SettingsViewModel,
    testUiState: SpeedTestUiState,
    historyItems: List<TestResult>,
    userSettings: UserSettings,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = SpeedTestRoutes.TEST,
        enterTransition = { fadeIn(animationSpec = tween(durationMillis = 180)) },
        exitTransition = { fadeOut(animationSpec = tween(durationMillis = 140)) },
        popEnterTransition = { fadeIn(animationSpec = tween(durationMillis = 180)) },
        popExitTransition = { fadeOut(animationSpec = tween(durationMillis = 140)) },
        modifier = modifier,
    ) {
        composable(SpeedTestRoutes.TEST) {
            TestScreen(
                uiState = testUiState,
                speedUnit = userSettings.speedUnit,
                onStartClick = speedTestViewModel::onStartClicked,
                onCancelClick = speedTestViewModel::onCancelClicked,
                onToggleGaugeMetric = speedTestViewModel::onToggleGaugeMetric,
                onConfirmMobileData = speedTestViewModel::onConfirmMobileDataWarning,
                onDismissMobileData = speedTestViewModel::onDismissMobileDataWarning,
                onDismissError = speedTestViewModel::onDismissError,
                onWifiPermissionResult = speedTestViewModel::onWifiPermissionResult,
                onPhonePermissionResult = speedTestViewModel::onPhonePermissionResult,
                onRefreshConnectionInfo = speedTestViewModel::onRefreshConnectionInfo,
                onOpenServerSelector = speedTestViewModel::onOpenServerSelector,
                onRefreshServers = speedTestViewModel::onRefreshServers,
                onSelectServer = speedTestViewModel::onSelectServer,
                onAddCustomServer = speedTestViewModel::onAddCustomServer,
                onDeleteCustomServer = speedTestViewModel::onDeleteCustomServer,
            )
        }

        composable(SpeedTestRoutes.HISTORY) {
            HistoryScreen(
                results = historyItems,
                speedUnit = userSettings.speedUnit,
                onResultClick = { id ->
                    navController.navigate(SpeedTestRoutes.historyDetail(id))
                },
                onDeleteResult = historyViewModel::deleteResult,
                onClearAll = historyViewModel::clearAllHistory,
            )
        }

        composable(
            route = SpeedTestRoutes.HISTORY_DETAIL,
            arguments = listOf(
                navArgument(SpeedTestRoutes.HISTORY_DETAIL_ARG) {
                    type = NavType.LongType
                },
            ),
        ) { backStackEntry ->
            val resultId = backStackEntry.arguments?.getLong(SpeedTestRoutes.HISTORY_DETAIL_ARG) ?: -1L
            val resultFlow = remember(resultId) { historyViewModel.observeResult(resultId) }
            val result by resultFlow.collectAsStateWithLifecycle(initialValue = null)
            HistoryDetailScreen(
                result = result,
                speedUnit = userSettings.speedUnit,
                onNavigateBack = { navController.popBackStack() },
                onDelete = historyViewModel::deleteResult,
            )
        }

        composable(SpeedTestRoutes.TOOLS) {
            ToolsScreen(
                onOpenNetworkInfo = { navController.navigate(SpeedTestRoutes.NETWORK_INFO) },
                onOpenDnsBenchmark = { navController.navigate(SpeedTestRoutes.DNS_BENCHMARK) },
                onOpenExtendedTest = { navController.navigate(SpeedTestRoutes.EXTENDED_TEST) },
            )
        }

        composable(SpeedTestRoutes.NETWORK_INFO) {
            val context = LocalContext.current
            val networkInfoViewModel: NetworkInfoViewModel = viewModel(
                factory = NetworkInfoViewModel.factory(context),
            )
            val uiState by networkInfoViewModel.uiState.collectAsStateWithLifecycle()
            NetworkInfoScreen(
                uiState = uiState,
                onNavigateBack = { navController.popBackStack() },
                onRefresh = networkInfoViewModel::onRefreshAll,
                onResumeRefresh = networkInfoViewModel::onResumeRefresh,
                onWifiPermissionResult = networkInfoViewModel::onWifiPermissionResult,
                onPhonePermissionResult = networkInfoViewModel::onPhonePermissionResult,
            )
        }

        composable(SpeedTestRoutes.DNS_BENCHMARK) {
            val dnsBenchmarkViewModel: DnsBenchmarkViewModel = viewModel(
                factory = DnsBenchmarkViewModel.factory(),
            )
            val uiState by dnsBenchmarkViewModel.uiState.collectAsStateWithLifecycle()
            DnsBenchmarkScreen(
                uiState = uiState,
                onNavigateBack = { navController.popBackStack() },
                onRunClick = dnsBenchmarkViewModel::onRunClicked,
                onCancelClick = dnsBenchmarkViewModel::onCancelClicked,
            )
        }

        composable(SpeedTestRoutes.EXTENDED_TEST) {
            val context = LocalContext.current
            val extendedTestViewModel: ExtendedTestViewModel = viewModel(
                factory = ExtendedTestViewModel.factory(context),
            )
            val uiState by extendedTestViewModel.uiState.collectAsStateWithLifecycle()
            val downloadSamples by extendedTestViewModel.downloadSamples.collectAsStateWithLifecycle()
            val uploadSamples by extendedTestViewModel.uploadSamples.collectAsStateWithLifecycle()
            ExtendedTestScreen(
                uiState = uiState,
                downloadSamples = downloadSamples,
                uploadSamples = uploadSamples,
                speedUnit = userSettings.speedUnit,
                onNavigateBack = { navController.popBackStack() },
                onSelectDuration = extendedTestViewModel::onSelectDuration,
                onStartClick = extendedTestViewModel::onStartClicked,
                onCancelClick = extendedTestViewModel::onCancelClicked,
                onConfirmMobileData = extendedTestViewModel::onConfirmMobileDataWarning,
                onDismissMobileData = extendedTestViewModel::onDismissMobileDataWarning,
                onDismissError = extendedTestViewModel::onDismissError,
            )
        }

        composable(SpeedTestRoutes.SETTINGS) {
            SettingsScreen(
                settings = userSettings,
                onThemeModeChange = settingsViewModel::setThemeMode,
                onDynamicColorChange = settingsViewModel::setDynamicColor,
                onSpeedUnitChange = settingsViewModel::setSpeedUnit,
                onLiteModeChange = settingsViewModel::setLiteMode,
                onHapticFeedbackChange = settingsViewModel::setHapticFeedback,
                onMobileDataWarningChange = settingsViewModel::setMobileDataWarning,
                onIncludeCommunityServersChange = settingsViewModel::setIncludeCommunityServers,
            )
        }
    }
}
