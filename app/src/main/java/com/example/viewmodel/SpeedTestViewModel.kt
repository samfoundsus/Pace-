package com.example.viewmodel

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.AppContainer
import com.example.data.constants.SpeedTestConstants
import com.example.data.local.HistoryRepository
import com.example.data.models.ConnectionType
import com.example.data.models.SpeedSample
import com.example.data.models.SpeedTestError
import com.example.data.models.SpeedTestProgress
import com.example.data.models.TestResult
import com.example.data.models.TestStage
import com.example.data.server.CustomServerResult
import com.example.data.server.ServerKind
import com.example.data.server.ServerProfile
import com.example.data.server.ServerRepository
import com.example.data.server.ServerSelectorUiState
import com.example.data.settings.SettingsRepository
import com.example.data.settings.UserSettings
import com.example.engine.download.DownloadEngine
import com.example.engine.latency.LatencyEngine
import com.example.engine.speedtest.NetworkMonitor
import com.example.engine.speedtest.NetworkSnapshot
import com.example.engine.speedtest.SpeedTestClientFactory
import com.example.engine.speedtest.SpeedTestEngine
import com.example.engine.speedtest.WifiBand
import com.example.engine.tools.ExtendedTestStats
import com.example.engine.upload.UploadEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

enum class GaugeDisplayMetric {
    DOWNLOAD,
    UPLOAD,
}

enum class HapticCue {
    TEST_STARTED,
    TEST_FINISHED,
}

sealed interface SnackbarNotice {
    data class SavedServerUnreachable(val serverName: String) : SnackbarNotice
    data class ServerSwitchedToCloudflare(val previousServerName: String) : SnackbarNotice
}

@Immutable
data class SpeedTestUiState(
    val connectionType: ConnectionType = ConnectionType.NONE,
    val networkName: String? = null,
    val cellularNetworkTag: String? = null,
    val wifiBand: WifiBand? = null,
    val wifiSsidHidden: Boolean = false,
    val hasWifiLocationPermission: Boolean = false,
    val isLocationEnabled: Boolean = false,
    val wifiPermissionDenied: Boolean = false,
    val wifiPermissionPermanentlyDenied: Boolean = false,
    val hasPhoneStatePermission: Boolean = false,
    val phonePermissionDenied: Boolean = false,
    val deviceModel: String = Build.MODEL?.trim().orEmpty(),
    val stage: TestStage = TestStage.IDLE,
    val isRunning: Boolean = false,
    val isWarmingUp: Boolean = false,
    val stageProgress: Float = 0f,
    val phaseDurationSeconds: Float = 10f,
    val liveSpeedMbps: Double = 0.0,
    val pingProgressCount: Int = 0,
    val pingTotalCount: Int = SpeedTestConstants.PING_REQUEST_COUNT,
    val livePingMs: Double? = null,
    val liveJitterMs: Double? = null,
    val liveDownloadMbps: Double? = null,
    val liveUploadMbps: Double? = null,
    val liveDownloadLoadedLatencyMs: Double? = null,
    val liveUploadLoadedLatencyMs: Double? = null,
    val downloadSamples: List<SpeedSample> = emptyList(),
    val uploadSamples: List<SpeedSample> = emptyList(),
    val completedResult: TestResult? = null,
    val gaugeDisplayMetric: GaugeDisplayMetric = GaugeDisplayMetric.DOWNLOAD,
    val error: SpeedTestError? = null,
    val showMobileDataWarning: Boolean = false,
    val serverSelector: ServerSelectorUiState = ServerSelectorUiState(),
    val activeTestServer: ServerProfile? = null,
    val isResolvingServer: Boolean = false,
) {
    val gaugeSpeedMbps: Double
        get() = if (stage == TestStage.COMPLETED && completedResult != null) {
            when (gaugeDisplayMetric) {
                GaugeDisplayMetric.DOWNLOAD -> completedResult.download ?: 0.0
                GaugeDisplayMetric.UPLOAD -> completedResult.upload ?: 0.0
            }
        } else {
            liveSpeedMbps
        }

    val activeGraphStage: TestStage
        get() = when {
            stage == TestStage.UPLOAD -> TestStage.UPLOAD
            stage == TestStage.DOWNLOAD -> TestStage.DOWNLOAD
            stage == TestStage.COMPLETED -> when (gaugeDisplayMetric) {
                GaugeDisplayMetric.UPLOAD -> if (uploadSamples.isNotEmpty()) TestStage.UPLOAD else TestStage.DOWNLOAD
                GaugeDisplayMetric.DOWNLOAD -> if (downloadSamples.isNotEmpty()) TestStage.DOWNLOAD else TestStage.UPLOAD
            }
            downloadSamples.isNotEmpty() -> TestStage.DOWNLOAD
            else -> TestStage.IDLE
        }

    val displayedPingMs: Double?
        get() = completedResult?.ping ?: livePingMs

    val displayedJitterMs: Double?
        get() = when {
            stage == TestStage.PING -> null
            completedResult != null -> completedResult.jitter
            else -> liveJitterMs
        }

    val displayedDownloadMbps: Double?
        get() = completedResult?.download ?: liveDownloadMbps

    val displayedUploadMbps: Double?
        get() = completedResult?.upload ?: liveUploadMbps

    val displayedDownloadLoadedLatencyMs: Double?
        get() = if (completedResult != null) completedResult.downloadLoadedLatency else liveDownloadLoadedLatencyMs

    val displayedUploadLoadedLatencyMs: Double?
        get() = if (completedResult != null) completedResult.uploadLoadedLatency else liveUploadLoadedLatencyMs
}

/**
 * ViewModel for the Pace Test screen.
 * Keeps UI free of OkHttp and networking code, auto-saves completed tests once to Room,
 * manages server selection and automatic Cloudflare fallback notices, and applies user preferences.
 */
class SpeedTestViewModel(
    private val speedTestEngine: SpeedTestEngine,
    private val networkMonitor: NetworkMonitor,
    private val historyRepository: HistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val serverRepository: ServerRepository,
    private val managedClients: List<OkHttpClient> = emptyList(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        initialStateFromSnapshot(networkMonitor.currentSnapshot()).copy(
            serverSelector = serverRepository.selectorState.value,
        ),
    )
    val uiState: StateFlow<SpeedTestUiState> = _uiState.asStateFlow()

    private val _hapticEvents = MutableSharedFlow<HapticCue>(extraBufferCapacity = 4)
    val hapticEvents: SharedFlow<HapticCue> = _hapticEvents.asSharedFlow()

    private val _snackbarNotices = MutableSharedFlow<SnackbarNotice>(extraBufferCapacity = 4)
    val snackbarNotices: SharedFlow<SnackbarNotice> = _snackbarNotices.asSharedFlow()

    private var currentSettings: UserSettings = UserSettings()
    private var activeTestJob: Job? = null
    private var lastSavedTimestamp: Long? = null

    init {
        networkMonitor.startObserving()
        viewModelScope.launch {
            networkMonitor.networkState.collect { snapshot ->
                _uiState.update { state -> state.applySnapshot(snapshot) }
            }
        }
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                currentSettings = settings
                serverRepository.onIncludeCommunitySettingChanged(settings.includeCommunityServers)
            }
        }
        viewModelScope.launch {
            serverRepository.selectorState.collect { selector ->
                _uiState.update { state -> state.copy(serverSelector = selector) }
            }
        }
    }

    fun onOpenServerSelector() {
        serverRepository.onSelectorOpened(currentSettings.includeCommunityServers)
    }

    fun onRefreshServers() {
        serverRepository.refreshServers(
            includeCommunityServers = currentSettings.includeCommunityServers,
            forceFetchCommunity = true,
        )
    }

    fun onSelectServer(serverId: String) {
        viewModelScope.launch {
            serverRepository.selectServer(serverId)
            _uiState.update { it.copy(activeTestServer = null) }
        }
    }

    fun onAddCustomServer(
        name: String,
        baseUrl: String,
        downloadPath: String,
        uploadPath: String,
        pingPath: String,
        getIpPath: String,
        onResult: (CustomServerResult) -> Unit,
    ) {
        viewModelScope.launch {
            val result = serverRepository.validateAndAddCustomServer(
                name = name,
                baseUrl = baseUrl,
                downloadPath = downloadPath,
                uploadPath = uploadPath,
                pingPath = pingPath,
                getIpPath = getIpPath,
            )
            if (result is CustomServerResult.Success) {
                _uiState.update { it.copy(activeTestServer = result.profile) }
            }
            onResult(result)
        }
    }

    fun onDeleteCustomServer(serverId: String) {
        viewModelScope.launch {
            serverRepository.deleteCustomServer(serverId)
        }
    }

    fun onWifiPermissionResult(granted: Boolean, permanentlyDenied: Boolean) {
        val snapshot = networkMonitor.refreshSnapshot(reregisterCallback = granted)
        _uiState.update { state ->
            state.applySnapshot(snapshot).copy(
                wifiPermissionDenied = !granted,
                wifiPermissionPermanentlyDenied = !granted && permanentlyDenied,
            )
        }
    }

    fun onPhonePermissionResult(granted: Boolean) {
        val snapshot = networkMonitor.refreshSnapshot(reregisterCallback = granted)
        _uiState.update { state ->
            state.applySnapshot(snapshot).copy(
                phonePermissionDenied = !granted,
            )
        }
    }

    fun onRefreshConnectionInfo() {
        val snapshot = networkMonitor.refreshSnapshot(reregisterCallback = true)
        _uiState.update { state ->
            state.applySnapshot(snapshot).let { updated ->
                updated.copy(
                    wifiPermissionDenied = if (updated.hasWifiLocationPermission) false else updated.wifiPermissionDenied,
                    wifiPermissionPermanentlyDenied = if (updated.hasWifiLocationPermission) false else updated.wifiPermissionPermanentlyDenied,
                    phonePermissionDenied = if (updated.hasPhoneStatePermission) false else updated.phonePermissionDenied,
                )
            }
        }
    }

    fun onStartClicked() {
        if (_uiState.value.isRunning) return

        val snapshot = networkMonitor.currentSnapshot()
        _uiState.update { it.applySnapshot(snapshot).copy(error = null) }

        when (snapshot.connectionType) {
            ConnectionType.NONE -> {
                _uiState.update {
                    it.copy(
                        stage = TestStage.IDLE,
                        isRunning = false,
                        completedResult = null,
                        error = SpeedTestError.NoInternet,
                    )
                }
            }
            ConnectionType.MOBILE -> {
                if (currentSettings.mobileDataWarning) {
                    _uiState.update { it.copy(showMobileDataWarning = true) }
                } else {
                    startSpeedTest()
                }
            }
            ConnectionType.WIFI, ConnectionType.ETHERNET -> {
                startSpeedTest()
            }
        }
    }

    fun onConfirmMobileDataWarning() {
        _uiState.update { it.copy(showMobileDataWarning = false) }
        val currentConnection = networkMonitor.currentSnapshot().connectionType
        if (currentConnection == ConnectionType.NONE) {
            _uiState.update { it.copy(error = SpeedTestError.NoInternet) }
            return
        }
        startSpeedTest()
    }

    fun onDismissMobileDataWarning() {
        _uiState.update { it.copy(showMobileDataWarning = false) }
    }

    fun onToggleGaugeMetric() {
        val state = _uiState.value
        if (state.stage != TestStage.COMPLETED || state.completedResult == null) return
        val next = when (state.gaugeDisplayMetric) {
            GaugeDisplayMetric.DOWNLOAD -> GaugeDisplayMetric.UPLOAD
            GaugeDisplayMetric.UPLOAD -> GaugeDisplayMetric.DOWNLOAD
        }
        _uiState.update { it.copy(gaugeDisplayMetric = next) }
    }

    fun onCancelClicked() {
        activeTestJob?.cancel()
        activeTestJob = null
        managedClients.forEach { it.dispatcher.cancelAll() }
        // Never save or retain an incomplete measurement as a successful result
        _uiState.update { state ->
            state.copy(
                stage = TestStage.IDLE,
                isRunning = false,
                isResolvingServer = false,
                isWarmingUp = false,
                stageProgress = 0f,
                liveSpeedMbps = 0.0,
                pingProgressCount = 0,
                livePingMs = null,
                liveJitterMs = null,
                liveDownloadMbps = null,
                liveUploadMbps = null,
                liveDownloadLoadedLatencyMs = null,
                liveUploadLoadedLatencyMs = null,
                downloadSamples = emptyList(),
                uploadSamples = emptyList(),
                completedResult = null,
                gaugeDisplayMetric = GaugeDisplayMetric.DOWNLOAD,
                error = null,
            )
        }
    }

    fun onDismissError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun startSpeedTest() {
        activeTestJob?.cancel()
        val useLiteMode = currentSettings.liteMode
        val includeCommunity = currentSettings.includeCommunityServers
        val durationSec = if (useLiteMode) {
            SpeedTestConstants.DOWNLOAD_LITE_DURATION_MS / 1000f
        } else {
            SpeedTestConstants.DOWNLOAD_DURATION_MS / 1000f
        }

        if (currentSettings.hapticFeedback) {
            _hapticEvents.tryEmit(HapticCue.TEST_STARTED)
        }

        _uiState.update { state ->
            state.copy(
                stage = TestStage.PING,
                isRunning = true,
                isResolvingServer = true,
                isWarmingUp = false,
                stageProgress = 0f,
                phaseDurationSeconds = durationSec,
                liveSpeedMbps = 0.0,
                pingProgressCount = 0,
                livePingMs = null,
                liveJitterMs = null,
                liveDownloadMbps = null,
                liveUploadMbps = null,
                liveDownloadLoadedLatencyMs = null,
                liveUploadLoadedLatencyMs = null,
                downloadSamples = emptyList(),
                uploadSamples = emptyList(),
                completedResult = null,
                gaugeDisplayMetric = GaugeDisplayMetric.DOWNLOAD,
                error = null,
            )
        }

        activeTestJob = viewModelScope.launch {
            val resolved = serverRepository.resolveServerForTest(
                includeCommunityServers = includeCommunity,
            )
            if (resolved.fellBackFromServerName != null) {
                _snackbarNotices.tryEmit(
                    SnackbarNotice.SavedServerUnreachable(resolved.fellBackFromServerName),
                )
            }
            _uiState.update { state ->
                state.copy(
                    isResolvingServer = false,
                    activeTestServer = resolved.server,
                )
            }

            speedTestEngine.runSpeedTest(
                server = resolved.server,
                liteMode = useLiteMode,
            ).collect { event ->
                handleProgress(event)
            }
        }
    }

    private fun handleProgress(event: SpeedTestProgress) {
        when (event) {
            is SpeedTestProgress.ServerActive -> {
                if (event.server.kind == ServerKind.CLOUDFLARE && event.server.location.isNotBlank()) {
                    serverRepository.updateCloudflareLocation(event.server.location)
                }
                _uiState.update { state ->
                    state.copy(activeTestServer = event.server)
                }
            }

            is SpeedTestProgress.ServerFallback -> {
                _snackbarNotices.tryEmit(
                    SnackbarNotice.ServerSwitchedToCloudflare(event.previousServerName),
                )
                _uiState.update { state ->
                    state.copy(activeTestServer = event.fallbackServer)
                }
            }

            is SpeedTestProgress.StageStarted -> {
                _uiState.update { state ->
                    state.copy(
                        stage = event.stage,
                        isWarmingUp = event.stage == TestStage.DOWNLOAD || event.stage == TestStage.UPLOAD,
                        stageProgress = 0f,
                        liveSpeedMbps = 0.0,
                        downloadSamples = if (event.stage == TestStage.DOWNLOAD) emptyList() else state.downloadSamples,
                        uploadSamples = if (event.stage == TestStage.UPLOAD) emptyList() else state.uploadSamples,
                        liveDownloadLoadedLatencyMs = if (event.stage == TestStage.DOWNLOAD) null else state.liveDownloadLoadedLatencyMs,
                        liveUploadLoadedLatencyMs = if (event.stage == TestStage.UPLOAD) null else state.liveUploadLoadedLatencyMs,
                    )
                }
            }

            is SpeedTestProgress.PingSampled -> {
                val fraction = event.completedCount.toFloat() / event.totalCount.coerceAtLeast(1)
                _uiState.update { state ->
                    state.copy(
                        pingProgressCount = event.completedCount,
                        pingTotalCount = event.totalCount,
                        stageProgress = fraction,
                        livePingMs = event.currentMedianPingMs,
                        liveJitterMs = null,
                    )
                }
            }

            is SpeedTestProgress.PingCompleted -> {
                _uiState.update { state ->
                    state.copy(
                        livePingMs = event.pingMs,
                        liveJitterMs = event.jitterMs,
                        stageProgress = 1f,
                    )
                }
            }

            is SpeedTestProgress.ThroughputUpdate -> {
                _uiState.update { state ->
                    when (event.stage) {
                        TestStage.DOWNLOAD -> state.copy(
                            liveSpeedMbps = event.currentSpeedMbps,
                            isWarmingUp = event.isWarmingUp,
                            stageProgress = event.progressFraction,
                            downloadSamples = ExtendedTestStats.downsampleSamples(
                                state.downloadSamples + event.sample,
                                ExtendedTestStats.MAX_GRAPH_POINTS,
                            ),
                        )
                        TestStage.UPLOAD -> state.copy(
                            liveSpeedMbps = event.currentSpeedMbps,
                            isWarmingUp = event.isWarmingUp,
                            stageProgress = event.progressFraction,
                            uploadSamples = ExtendedTestStats.downsampleSamples(
                                state.uploadSamples + event.sample,
                                ExtendedTestStats.MAX_GRAPH_POINTS,
                            ),
                        )
                        else -> state
                    }
                }
            }

            is SpeedTestProgress.LoadedLatencySampled -> {
                _uiState.update { state ->
                    when (event.stage) {
                        TestStage.DOWNLOAD -> state.copy(liveDownloadLoadedLatencyMs = event.medianLatencyMs)
                        TestStage.UPLOAD -> state.copy(liveUploadLoadedLatencyMs = event.medianLatencyMs)
                        else -> state
                    }
                }
            }

            is SpeedTestProgress.DownloadCompleted -> {
                _uiState.update { state ->
                    state.copy(
                        liveDownloadMbps = event.downloadMbps,
                        liveDownloadLoadedLatencyMs = event.loadedLatencyMs,
                        liveSpeedMbps = 0.0,
                        isWarmingUp = false,
                        stageProgress = 1f,
                    )
                }
            }

            is SpeedTestProgress.UploadCompleted -> {
                _uiState.update { state ->
                    state.copy(
                        liveUploadMbps = event.uploadMbps,
                        liveUploadLoadedLatencyMs = event.loadedLatencyMs,
                        isWarmingUp = false,
                        stageProgress = 1f,
                    )
                }
            }

            is SpeedTestProgress.Completed -> {
                val result = event.result
                if (result.serverName == ServerProfile.CLOUDFLARE.name && !result.serverLocation.isNullOrBlank()) {
                    serverRepository.updateCloudflareLocation(result.serverLocation)
                }
                _uiState.update { state ->
                    state.copy(
                        stage = TestStage.COMPLETED,
                        isRunning = false,
                        isResolvingServer = false,
                        isWarmingUp = false,
                        stageProgress = 1f,
                        liveSpeedMbps = result.download ?: 0.0,
                        completedResult = result,
                        gaugeDisplayMetric = GaugeDisplayMetric.DOWNLOAD,
                        error = null,
                    )
                }
                if (currentSettings.hapticFeedback) {
                    _hapticEvents.tryEmit(HapticCue.TEST_FINISHED)
                }
                saveCompletedResultOnce(result)
                activeTestJob = null
            }

            is SpeedTestProgress.Failed -> {
                // Never save or show partial measurements as a completed result on failure or network change
                _uiState.update { state ->
                    state.copy(
                        stage = TestStage.IDLE,
                        isRunning = false,
                        isResolvingServer = false,
                        isWarmingUp = false,
                        stageProgress = 0f,
                        liveSpeedMbps = 0.0,
                        pingProgressCount = 0,
                        livePingMs = null,
                        liveJitterMs = null,
                        liveDownloadMbps = null,
                        liveUploadMbps = null,
                        liveDownloadLoadedLatencyMs = null,
                        liveUploadLoadedLatencyMs = null,
                        downloadSamples = emptyList(),
                        uploadSamples = emptyList(),
                        completedResult = null,
                        gaugeDisplayMetric = GaugeDisplayMetric.DOWNLOAD,
                        error = event.error,
                    )
                }
                activeTestJob = null
            }
        }
    }

    private fun saveCompletedResultOnce(result: TestResult) {
        if (lastSavedTimestamp == result.timestamp) return
        lastSavedTimestamp = result.timestamp
        viewModelScope.launch {
            runCatching {
                historyRepository.saveResult(result)
            }
        }
    }

    private fun SpeedTestUiState.applySnapshot(snapshot: NetworkSnapshot): SpeedTestUiState = copy(
        connectionType = snapshot.connectionType,
        networkName = snapshot.networkName,
        cellularNetworkTag = snapshot.cellularNetworkTag,
        wifiBand = snapshot.wifiBand,
        wifiSsidHidden = snapshot.wifiSsidHidden,
        hasWifiLocationPermission = snapshot.hasWifiLocationPermission,
        isLocationEnabled = snapshot.isLocationEnabled,
        hasPhoneStatePermission = snapshot.hasPhoneStatePermission,
        phonePermissionDenied = if (snapshot.hasPhoneStatePermission) false else phonePermissionDenied,
        deviceModel = snapshot.deviceModel,
    )

    override fun onCleared() {
        activeTestJob?.cancel()
        activeTestJob = null
        networkMonitor.stopObserving()
        managedClients.forEach { client ->
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
        }
        super.onCleared()
    }

    companion object {
        private fun initialStateFromSnapshot(snapshot: NetworkSnapshot): SpeedTestUiState = SpeedTestUiState(
            connectionType = snapshot.connectionType,
            networkName = snapshot.networkName,
            cellularNetworkTag = snapshot.cellularNetworkTag,
            wifiBand = snapshot.wifiBand,
            wifiSsidHidden = snapshot.wifiSsidHidden,
            hasWifiLocationPermission = snapshot.hasWifiLocationPermission,
            isLocationEnabled = snapshot.isLocationEnabled,
            hasPhoneStatePermission = snapshot.hasPhoneStatePermission,
            deviceModel = snapshot.deviceModel,
        )

        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val appContext = context.applicationContext
                val container = AppContainer.getInstance(appContext)
                val throughputClient = SpeedTestClientFactory.createThroughputClient()
                val latencyClient = SpeedTestClientFactory.createLatencyClient()
                val loadedLatencyClient = SpeedTestClientFactory.createLoadedLatencyClient()
                val networkMonitor = NetworkMonitor(appContext)
                val engine = SpeedTestEngine(
                    latencyEngine = LatencyEngine(latencyClient),
                    loadedLatencyEngine = LatencyEngine(loadedLatencyClient),
                    downloadEngine = DownloadEngine(throughputClient),
                    uploadEngine = UploadEngine(throughputClient),
                    networkMonitor = networkMonitor,
                )
                return SpeedTestViewModel(
                    speedTestEngine = engine,
                    networkMonitor = networkMonitor,
                    historyRepository = container.historyRepository,
                    settingsRepository = container.settingsRepository,
                    serverRepository = container.serverRepository,
                    managedClients = listOf(throughputClient, latencyClient, loadedLatencyClient),
                ) as T
            }
        }
    }
}
