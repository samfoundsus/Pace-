package com.example.ui.screens

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
    const val CRASH_REPORT = "settings/crash_report"

    fun historyDetail(resultId: Long): String = "history_detail/$resultId"

    fun isSubScreen(route: String?): Boolean = when (route) {
        HISTORY_DETAIL,
        NETWORK_INFO,
        DNS_BENCHMARK,
        EXTENDED_TEST,
        CRASH_REPORT,
        -> true
        else -> false
    }
}

private data class SpeedTestDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val testTag: String,
)

private fun readAnimatorDurationScale(context: Context): Float {
    return runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
    }.getOrDefault(1f)
}

@Composable
private fun rememberAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var animatorScale by remember(context) {
        mutableFloatStateOf(readAnimatorDurationScale(context))
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                animatorScale = readAnimatorDurationScale(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    return animatorScale > 0f
}

@Composable
fun SpeedTestApp(
    userSettings: UserSettings,
    settingsViewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val animationsEnabled = rememberAnimationsEnabled()

    val speedTestViewModel: SpeedTestViewModel = viewModel(
        factory = SpeedTestViewModel.factory(context),
    )
    val historyViewModel: HistoryViewModel = viewModel(
        factory = HistoryViewModel.factory(context),
    )

    val testUiState by speedTestViewModel.uiState.collectAsStateWithLifecycle()
    val historyItems by historyViewModel.historyItems.collectAsStateWithLifecycle()
    val hasCrashReport by settingsViewModel.hasCrashReport.collectAsStateWithLifecycle()

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
    val isDetailScreen = SpeedTestRoutes.isSubScreen(currentRoute)
    val selectedTopRoute = when (currentRoute) {
        SpeedTestRoutes.HISTORY_DETAIL -> SpeedTestRoutes.HISTORY
        SpeedTestRoutes.NETWORK_INFO,
        SpeedTestRoutes.DNS_BENCHMARK,
        SpeedTestRoutes.EXTENDED_TEST,
        -> SpeedTestRoutes.TOOLS
        SpeedTestRoutes.CRASH_REPORT -> SpeedTestRoutes.SETTINGS
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
                        hasCrashReport = hasCrashReport,
                        userSettings = userSettings,
                        animationsEnabled = animationsEnabled,
                        topLevelBottomPadding = 0.dp,
                        applyNavBarInsetsToSubScreens = false,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        } else {
            var measuredBottomBarHeightPx by remember { mutableIntStateOf(0) }
            val navBarInsetBottom = with(density) {
                WindowInsets.navigationBars.getBottom(density).toDp()
            }
            val bottomBarClearance = if (measuredBottomBarHeightPx > 0) {
                with(density) { measuredBottomBarHeightPx.toDp() }
            } else {
                80.dp + navBarInsetBottom
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                        ),
                    ),
            ) {
                SpeedTestNavHost(
                    navController = navController,
                    speedTestViewModel = speedTestViewModel,
                    historyViewModel = historyViewModel,
                    settingsViewModel = settingsViewModel,
                    testUiState = testUiState,
                    historyItems = historyItems,
                    hasCrashReport = hasCrashReport,
                    userSettings = userSettings,
                    animationsEnabled = animationsEnabled,
                    topLevelBottomPadding = bottomBarClearance,
                    applyNavBarInsetsToSubScreens = true,
                    modifier = Modifier.fillMaxSize(),
                )

                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(
                            bottom = if (!isDetailScreen) bottomBarClearance else navBarInsetBottom,
                        ),
                )

                AnimatedVisibility(
                    visible = !isDetailScreen,
                    enter = if (animationsEnabled) {
                        fadeIn(
                            animationSpec = tween(
                                durationMillis = 210,
                                easing = FastOutSlowInEasing,
                            ),
                        ) + slideInVertically(
                            animationSpec = tween(
                                durationMillis = 220,
                                easing = FastOutSlowInEasing,
                            ),
                        ) { fullHeight -> fullHeight / 2 }
                    } else {
                        EnterTransition.None
                    },
                    exit = if (animationsEnabled) {
                        fadeOut(
                            animationSpec = tween(
                                durationMillis = 140,
                                easing = FastOutSlowInEasing,
                            ),
                        ) + slideOutVertically(
                            animationSpec = tween(
                                durationMillis = 160,
                                easing = FastOutSlowInEasing,
                            ),
                        ) { fullHeight -> fullHeight / 2 }
                    } else {
                        ExitTransition.None
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.onSizeChanged { size ->
                            if (size.height > 0) {
                                measuredBottomBarHeightPx = size.height
                            }
                        },
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
    hasCrashReport: Boolean,
    userSettings: UserSettings,
    animationsEnabled: Boolean,
    topLevelBottomPadding: Dp,
    applyNavBarInsetsToSubScreens: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val sharedAxisSlidePx = remember(density) {
        with(density) { 30.dp.roundToPx() }
    }

    val popBackSafely: () -> Unit = {
        if (navController.previousBackStackEntry != null) {
            navController.popBackStack()
        }
    }

    val topLevelModifier = if (topLevelBottomPadding > 0.dp) {
        Modifier
            .fillMaxSize()
            .padding(bottom = topLevelBottomPadding)
    } else {
        Modifier.fillMaxSize()
    }

    val subScreenModifier = if (applyNavBarInsetsToSubScreens) {
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBars)
    } else {
        Modifier.fillMaxSize()
    }

    NavHost(
        navController = navController,
        startDestination = SpeedTestRoutes.TEST,
        sizeTransform = null,
        enterTransition = {
            if (!animationsEnabled) {
                EnterTransition.None
            } else if (SpeedTestRoutes.isSubScreen(targetState.destination.route)) {
                // Material shared axis X (forward enter): +30dp slide + fade in
                slideInHorizontally(
                    animationSpec = tween(
                        durationMillis = 260,
                        easing = FastOutSlowInEasing,
                    ),
                ) { sharedAxisSlidePx } + fadeIn(
                    animationSpec = tween(
                        durationMillis = 210,
                        delayMillis = 50,
                        easing = FastOutSlowInEasing,
                    ),
                )
            } else {
                // Material fade through (bottom tabs): 90ms out, then 210ms fade in + scale 0.92 -> 1
                fadeIn(
                    animationSpec = tween(
                        durationMillis = 210,
                        delayMillis = 90,
                        easing = FastOutSlowInEasing,
                    ),
                ) + scaleIn(
                    initialScale = 0.92f,
                    animationSpec = tween(
                        durationMillis = 210,
                        delayMillis = 90,
                        easing = FastOutSlowInEasing,
                    ),
                )
            }
        },
        exitTransition = {
            if (!animationsEnabled) {
                ExitTransition.None
            } else if (SpeedTestRoutes.isSubScreen(targetState.destination.route)) {
                // Material shared axis X (forward exit of parent screen): -30dp slide + fade out
                slideOutHorizontally(
                    animationSpec = tween(
                        durationMillis = 200,
                        easing = FastOutSlowInEasing,
                    ),
                ) { -sharedAxisSlidePx } + fadeOut(
                    animationSpec = tween(
                        durationMillis = 90,
                        easing = FastOutSlowInEasing,
                    ),
                )
            } else {
                // Material fade through (bottom tabs): 90ms fade out
                fadeOut(
                    animationSpec = tween(
                        durationMillis = 90,
                        easing = FastOutSlowInEasing,
                    ),
                )
            }
        },
        popEnterTransition = {
            if (!animationsEnabled) {
                EnterTransition.None
            } else if (SpeedTestRoutes.isSubScreen(initialState.destination.route)) {
                // Material shared axis X (reverse back enter of parent screen): -30dp -> 0 + fade in
                slideInHorizontally(
                    animationSpec = tween(
                        durationMillis = 240,
                        easing = FastOutSlowInEasing,
                    ),
                ) { -sharedAxisSlidePx } + fadeIn(
                    animationSpec = tween(
                        durationMillis = 180,
                        delayMillis = 40,
                        easing = FastOutSlowInEasing,
                    ),
                )
            } else {
                fadeIn(
                    animationSpec = tween(
                        durationMillis = 210,
                        delayMillis = 90,
                        easing = FastOutSlowInEasing,
                    ),
                ) + scaleIn(
                    initialScale = 0.92f,
                    animationSpec = tween(
                        durationMillis = 210,
                        delayMillis = 90,
                        easing = FastOutSlowInEasing,
                    ),
                )
            }
        },
        popExitTransition = {
            if (!animationsEnabled) {
                ExitTransition.None
            } else if (SpeedTestRoutes.isSubScreen(initialState.destination.route)) {
                // Material shared axis X (reverse back exit of sub-screen): 0 -> +30dp + fade out
                slideOutHorizontally(
                    animationSpec = tween(
                        durationMillis = 220,
                        easing = FastOutSlowInEasing,
                    ),
                ) { sharedAxisSlidePx } + fadeOut(
                    animationSpec = tween(
                        durationMillis = 120,
                        easing = FastOutSlowInEasing,
                    ),
                )
            } else {
                fadeOut(
                    animationSpec = tween(
                        durationMillis = 90,
                        easing = FastOutSlowInEasing,
                    ),
                )
            }
        },
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
                modifier = topLevelModifier,
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
                modifier = topLevelModifier,
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
                onNavigateBack = popBackSafely,
                onDelete = historyViewModel::deleteResult,
                modifier = subScreenModifier,
            )
        }

        composable(SpeedTestRoutes.TOOLS) {
            ToolsScreen(
                onOpenNetworkInfo = { navController.navigate(SpeedTestRoutes.NETWORK_INFO) },
                onOpenDnsBenchmark = { navController.navigate(SpeedTestRoutes.DNS_BENCHMARK) },
                onOpenExtendedTest = { navController.navigate(SpeedTestRoutes.EXTENDED_TEST) },
                modifier = topLevelModifier,
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
                onNavigateBack = popBackSafely,
                onRefresh = networkInfoViewModel::onRefreshAll,
                onResumeRefresh = networkInfoViewModel::onResumeRefresh,
                onWifiPermissionResult = networkInfoViewModel::onWifiPermissionResult,
                onPhonePermissionResult = networkInfoViewModel::onPhonePermissionResult,
                modifier = subScreenModifier,
            )
        }

        composable(SpeedTestRoutes.DNS_BENCHMARK) {
            val dnsBenchmarkViewModel: DnsBenchmarkViewModel = viewModel(
                factory = DnsBenchmarkViewModel.factory(),
            )
            val uiState by dnsBenchmarkViewModel.uiState.collectAsStateWithLifecycle()
            DnsBenchmarkScreen(
                uiState = uiState,
                onNavigateBack = popBackSafely,
                onRunClick = dnsBenchmarkViewModel::onRunClicked,
                onCancelClick = dnsBenchmarkViewModel::onCancelClicked,
                modifier = subScreenModifier,
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
                onNavigateBack = popBackSafely,
                onSelectDuration = extendedTestViewModel::onSelectDuration,
                onStartClick = extendedTestViewModel::onStartClicked,
                onCancelClick = extendedTestViewModel::onCancelClicked,
                onConfirmMobileData = extendedTestViewModel::onConfirmMobileDataWarning,
                onDismissMobileData = extendedTestViewModel::onDismissMobileDataWarning,
                onDismissError = extendedTestViewModel::onDismissError,
                modifier = subScreenModifier,
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
                hasCrashReport = hasCrashReport,
                onOpenCrashReport = { navController.navigate(SpeedTestRoutes.CRASH_REPORT) },
                onRefreshCrashReport = settingsViewModel::refreshCrashReportState,
                modifier = topLevelModifier,
            )
        }

        composable(SpeedTestRoutes.CRASH_REPORT) {
            val reportText = remember { settingsViewModel.readLastCrashReport() }
            CrashReportScreen(
                reportText = reportText,
                onNavigateBack = popBackSafely,
                onDeleteReport = {
                    settingsViewModel.deleteLastCrashReport()
                    popBackSafely()
                },
                modifier = subScreenModifier,
            )
        }
    }
}
