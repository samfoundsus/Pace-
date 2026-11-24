package com.example.viewmodel

import android.content.Context
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
import com.example.data.models.TestStage
import com.example.data.server.ServerKind
import com.example.data.server.ServerProfile
import com.example.data.server.ServerRepository
import com.example.data.settings.SettingsRepository
import com.example.data.settings.UserSettings
import com.example.engine.download.DownloadEngine
import com.example.engine.download.DownloadEvent
import com.example.engine.latency.ServerHttpException
import com.example.engine.speedtest.NetworkMonitor
import com.example.engine.speedtest.ServerNoDataTimeoutException
import com.example.engine.speedtest.SpeedTestClientFactory
import com.example.engine.speedtest.WifiBand
import com.example.engine.tools.ExtendedDuration
import com.example.engine.tools.ExtendedTestStats
import com.example.engine.tools.PhaseSummaryStats
import com.example.engine.upload.UploadEngine
import com.example.engine.upload.UploadEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicReference

@Immutable
data class ExtendedTestUiState(
    val selectedDuration: ExtendedDuration = ExtendedDuration.THIRTY_SECONDS,
    val runDurationSeconds: Int = ExtendedDuration.THIRTY_SECONDS.seconds,
    val connectionType: ConnectionType = ConnectionType.NONE,
    val wifiBand: WifiBand? = null,
    val cellularNetworkTag: String? = null,
    val lastDownloadMbps: Double? = null,
    val lastUploadMbps: Double? = null,
    val stage: TestStage = TestStage.IDLE,
    val isRunning: Boolean = false,
    val isWarmingUp: Boolean = false,
    val elapsedSeconds: Int = 0,
    val liveSpeedMbps: Double = 0.0,
    val downloadSamples: List<SpeedSample> = emptyList(),
    val uploadSamples: List<SpeedSample> = emptyList(),
    val downloadSummary: PhaseSummaryStats? = null,
    val uploadSummary: PhaseSummaryStats? = null,
    val showMobileDataWarning: Boolean = false,
    val wasCancelled: Boolean = false,
    val error: SpeedTestError? = null,
) {
    val estimatedDataMb: Double?
        get() = ExtendedTestStats.estimateDataUsageMb(
            lastDownloadMbps = lastDownloadMbps,
            lastUploadMbps = lastUploadMbps,
            phaseSeconds = selectedDuration.seconds,
        )

    val phaseDurationSeconds: Float
        get() = runDurationSeconds.coerceAtLeast(1).toFloat()

    val remainingSeconds: Int
        get() = (runDurationSeconds - elapsedSeconds).coerceIn(0, runDurationSeconds)

    val hasAnyGraphData: Boolean
        get() = downloadSamples.isNotEmpty() || uploadSamples.isNotEmpty()

    /**
     * "Run again" is shown strictly after a completed run where both phases produced graph curves
     * and stability summaries—never when graphs are empty or a phase failed/was cancelled.
     */
    val canRunAgain: Boolean
        get() = !isRunning &&
            stage == TestStage.COMPLETED &&
            downloadSamples.isNotEmpty() &&
            uploadSamples.isNotEmpty() &&
            downloadSummary != null &&
            uploadSummary != null
}

/**
 * ViewModel for the Extended Test tool (30s or 60s per phase).
 * Uses the active server resolved from [ServerRepository] (with Cloudflare fallback if stalled),
 * emits throttled (~8 Hz) smoothed throughput samples into dedicated StateFlow lists per phase,
 * caps graph series at 300 points, excludes the 1.5s warm-up from Stability summary statistics,
 * preserves partial graphs on cancel or error, and intentionally never persists results to History.
 */
class ExtendedTestViewModel(
    private val downloadEngine: DownloadEngine,
    private val uploadEngine: UploadEngine,
    private val networkMonitor: NetworkMonitor,
    private val historyRepository: HistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val serverRepository: ServerRepository,
    private val throughputClient: OkHttpClient,
) : ViewModel() {

    private val _downloadSamples = MutableStateFlow<List<SpeedSample>>(emptyList())
    val downloadSamples: StateFlow<List<SpeedSample>> = _downloadSamples.asStateFlow()

    private val _uploadSamples = MutableStateFlow<List<SpeedSample>>(emptyList())
    val uploadSamples: StateFlow<List<SpeedSample>> = _uploadSamples.asStateFlow()

    private val _uiState = MutableStateFlow(
        networkMonitor.currentSnapshot().let { snapshot ->
            ExtendedTestUiState(
                connectionType = snapshot.connectionType,
                wifiBand = snapshot.wifiBand,
                cellularNetworkTag = snapshot.cellularNetworkTag,
            )
        },
    )
    val uiState: StateFlow<ExtendedTestUiState> = _uiState.asStateFlow()

    private var currentSettings: UserSettings = UserSettings()
    private var activeJob: Job? = null

    init {
        networkMonitor.startObserving()
        viewModelScope.launch {
            networkMonitor.networkState.collect { snapshot ->
                _uiState.update {
                    it.copy(
                        connectionType = snapshot.connectionType,
                        wifiBand = snapshot.wifiBand,
                        cellularNetworkTag = snapshot.cellularNetworkTag,
                    )
                }
            }
        }
        viewModelScope.launch {
            historyRepository.allResults.collect { results ->
                val latest = results.firstOrNull()
                _uiState.update { state ->
                    state.copy(
                        lastDownloadMbps = latest?.download,
                        lastUploadMbps = latest?.upload,
                    )
                }
            }
        }
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                currentSettings = settings
            }
        }
    }

    fun onSelectDuration(duration: ExtendedDuration) {
        if (_uiState.value.isRunning) return
        _uiState.update { state ->
            state.copy(
                selectedDuration = duration,
                runDurationSeconds = if (state.hasAnyGraphData) state.runDurationSeconds else duration.seconds,
            )
        }
    }

    fun onStartClicked() {
        if (_uiState.value.isRunning) return

        val snapshot = networkMonitor.currentSnapshot()
        _uiState.update {
            it.copy(
                connectionType = snapshot.connectionType,
                wifiBand = snapshot.wifiBand,
                cellularNetworkTag = snapshot.cellularNetworkTag,
                wasCancelled = false,
                error = null,
            )
        }

        when (snapshot.connectionType) {
            ConnectionType.NONE -> {
                _uiState.update {
                    it.copy(
                        stage = TestStage.IDLE,
                        isRunning = false,
                        error = SpeedTestError.NoInternet,
                    )
                }
            }
            ConnectionType.MOBILE -> {
                if (currentSettings.mobileDataWarning) {
                    _uiState.update { it.copy(showMobileDataWarning = true) }
                } else {
                    startExtendedTest()
                }
            }
            ConnectionType.WIFI, ConnectionType.ETHERNET -> startExtendedTest()
        }
    }

    fun onConfirmMobileDataWarning() {
        _uiState.update { it.copy(showMobileDataWarning = false) }
        val connectionType = networkMonitor.currentSnapshot().connectionType
        if (connectionType == ConnectionType.NONE) {
            _uiState.update { it.copy(error = SpeedTestError.NoInternet) }
            return
        }
        startExtendedTest()
    }

    fun onDismissMobileDataWarning() {
        _uiState.update { it.copy(showMobileDataWarning = false) }
    }

    fun onDismissError() {
        _uiState.update { it.copy(error = null, wasCancelled = false) }
    }

    fun onCancelClicked() {
        val jobToCancel = activeJob
        activeJob = null
        jobToCancel?.cancel()
        throughputClient.dispatcher.cancelAll()

        // Preserve any partial curves and completed summaries collected so far.
        val keptDownload = _downloadSamples.value
        val keptUpload = _uploadSamples.value
        _uiState.update { state ->
            state.copy(
                stage = TestStage.IDLE,
                isRunning = false,
                isWarmingUp = false,
                liveSpeedMbps = 0.0,
                downloadSamples = keptDownload,
                uploadSamples = keptUpload,
                wasCancelled = true,
                error = null,
            )
        }
    }

    private fun startExtendedTest() {
        activeJob?.cancel()
        val duration = _uiState.value.selectedDuration
        val durationSeconds = duration.seconds
        val durationMs = durationSeconds * 1000L
        val initialSnapshot = networkMonitor.currentSnapshot()
        val includeCommunity = currentSettings.includeCommunityServers

        _downloadSamples.value = emptyList()
        _uploadSamples.value = emptyList()

        _uiState.update { state ->
            state.copy(
                runDurationSeconds = durationSeconds,
                stage = TestStage.DOWNLOAD,
                isRunning = true,
                isWarmingUp = true,
                elapsedSeconds = 0,
                liveSpeedMbps = 0.0,
                downloadSamples = emptyList(),
                uploadSamples = emptyList(),
                downloadSummary = null,
                uploadSummary = null,
                wasCancelled = false,
                error = null,
            )
        }

        activeJob = viewModelScope.launch(Dispatchers.IO) {
            val allDownloadSamples = ArrayList<SpeedSample>(durationSeconds * 10)
            val allUploadSamples = ArrayList<SpeedSample>(durationSeconds * 10)
            val networkInterruption = AtomicReference<SpeedTestError?>(null)

            try {
                var activeServer = serverRepository.resolveServerForTest(
                    includeCommunityServers = includeCommunity,
                ).server

                coroutineScope {
                    var executionJob: Job? = null

                    val monitorJob = launch(
                        context = Dispatchers.IO,
                        start = CoroutineStart.LAZY,
                    ) {
                        networkMonitor.networkState.collect { snapshot ->
                            val interruption = when {
                                snapshot.connectionType == ConnectionType.NONE -> SpeedTestError.NetworkLost
                                initialSnapshot.connectionType != ConnectionType.NONE &&
                                    snapshot.connectionType != initialSnapshot.connectionType -> SpeedTestError.NetworkChanged
                                initialSnapshot.networkHandle != null &&
                                    snapshot.networkHandle != null &&
                                    snapshot.networkHandle != initialSnapshot.networkHandle -> SpeedTestError.NetworkChanged
                                else -> null
                            }
                            if (interruption != null && networkInterruption.compareAndSet(null, interruption)) {
                                executionJob?.cancel(CancellationException("Network interrupted"))
                            }
                        }
                    }

                    suspend fun collectDownload(server: ServerProfile) {
                        var lastDownloadEmitNanos = 0L
                        downloadEngine.measureDownload(
                            server = server,
                            streamCount = SpeedTestConstants.DOWNLOAD_STREAM_COUNT,
                            durationMs = durationMs,
                            warmUpMs = SpeedTestConstants.DOWNLOAD_WARMUP_MS,
                            sampleIntervalMs = SpeedTestConstants.SAMPLE_INTERVAL_MS,
                            noDataTimeoutMs = if (server.kind != ServerKind.CLOUDFLARE) {
                                SpeedTestConstants.NO_DATA_FALLBACK_TIMEOUT_MS
                            } else {
                                null
                            },
                        ).collect { event ->
                            when (event) {
                                is DownloadEvent.Progress -> {
                                    allDownloadSamples.add(event.sample)
                                    val nowNanos = System.nanoTime()
                                    val shouldEmit = lastDownloadEmitNanos == 0L ||
                                        (nowNanos - lastDownloadEmitNanos) >= EMIT_THROTTLE_NANOS ||
                                        event.progressFraction >= 1f
                                    if (shouldEmit) {
                                        lastDownloadEmitNanos = nowNanos
                                        val immutableGraphSnapshot = ExtendedTestStats.downsampleSamples(
                                            allDownloadSamples,
                                            ExtendedTestStats.MAX_GRAPH_POINTS,
                                        )
                                        _downloadSamples.value = immutableGraphSnapshot
                                        _uiState.update { state ->
                                            state.copy(
                                                stage = TestStage.DOWNLOAD,
                                                isWarmingUp = event.isWarmingUp,
                                                elapsedSeconds = event.sample.elapsedSeconds.toInt()
                                                    .coerceIn(0, durationSeconds),
                                                liveSpeedMbps = event.smoothedMbps,
                                                downloadSamples = immutableGraphSnapshot,
                                            )
                                        }
                                    }
                                }
                                is DownloadEvent.Completed -> Unit
                            }
                        }
                    }

                    suspend fun collectUpload(server: ServerProfile) {
                        var lastUploadEmitNanos = 0L
                        uploadEngine.measureUpload(
                            server = server,
                            streamCount = SpeedTestConstants.UPLOAD_STREAM_COUNT,
                            durationMs = durationMs,
                            warmUpMs = SpeedTestConstants.UPLOAD_WARMUP_MS,
                            sampleIntervalMs = SpeedTestConstants.SAMPLE_INTERVAL_MS,
                            noDataTimeoutMs = if (server.kind != ServerKind.CLOUDFLARE) {
                                SpeedTestConstants.NO_DATA_FALLBACK_TIMEOUT_MS
                            } else {
                                null
                            },
                        ).collect { event ->
                            when (event) {
                                is UploadEvent.Progress -> {
                                    allUploadSamples.add(event.sample)
                                    val nowNanos = System.nanoTime()
                                    val shouldEmit = lastUploadEmitNanos == 0L ||
                                        (nowNanos - lastUploadEmitNanos) >= EMIT_THROTTLE_NANOS ||
                                        event.progressFraction >= 1f
                                    if (shouldEmit) {
                                        lastUploadEmitNanos = nowNanos
                                        val immutableGraphSnapshot = ExtendedTestStats.downsampleSamples(
                                            allUploadSamples,
                                            ExtendedTestStats.MAX_GRAPH_POINTS,
                                        )
                                        _uploadSamples.value = immutableGraphSnapshot
                                        _uiState.update { state ->
                                            state.copy(
                                                stage = TestStage.UPLOAD,
                                                isWarmingUp = event.isWarmingUp,
                                                elapsedSeconds = event.sample.elapsedSeconds.toInt()
                                                    .coerceIn(0, durationSeconds),
                                                liveSpeedMbps = event.smoothedMbps,
                                                uploadSamples = immutableGraphSnapshot,
                                            )
                                        }
                                    }
                                }
                                is UploadEvent.Completed -> Unit
                            }
                        }
                    }

                    executionJob = launch(Dispatchers.IO) {
                        // Phase 1: Sustained Download (with Cloudflare fallback on 5s stall)
                        try {
                            collectDownload(activeServer)
                        } catch (e: IOException) {
                            if (activeServer.kind != ServerKind.CLOUDFLARE && networkInterruption.get() == null) {
                                allDownloadSamples.clear()
                                _downloadSamples.value = emptyList()
                                activeServer = ServerProfile.CLOUDFLARE
                                collectDownload(activeServer)
                            } else {
                                throw e
                            }
                        }

                        // Flush final download curve snapshot
                        val finalDownloadGraph = ExtendedTestStats.downsampleSamples(
                            allDownloadSamples,
                            ExtendedTestStats.MAX_GRAPH_POINTS,
                        )
                        _downloadSamples.value = finalDownloadGraph

                        val downloadSummary = ExtendedTestStats.computePhaseSummaryExcludingWarmup(
                            allSamples = allDownloadSamples,
                            warmUpSeconds = ExtendedTestStats.WARMUP_SECONDS,
                        )
                        if (finalDownloadGraph.isEmpty() || downloadSummary == null) {
                            throw IOException("No download throughput samples collected")
                        }

                        _uiState.update { state ->
                            state.copy(
                                stage = TestStage.UPLOAD,
                                isWarmingUp = true,
                                elapsedSeconds = 0,
                                liveSpeedMbps = 0.0,
                                downloadSamples = finalDownloadGraph,
                                downloadSummary = downloadSummary,
                            )
                        }

                        // Phase 2: Sustained Upload (with Cloudflare fallback on 5s stall)
                        try {
                            collectUpload(activeServer)
                        } catch (e: IOException) {
                            if (activeServer.kind != ServerKind.CLOUDFLARE && networkInterruption.get() == null) {
                                allUploadSamples.clear()
                                _uploadSamples.value = emptyList()
                                activeServer = ServerProfile.CLOUDFLARE
                                collectUpload(activeServer)
                            } else {
                                throw e
                            }
                        }

                        // Flush final upload curve snapshot
                        val finalUploadGraph = ExtendedTestStats.downsampleSamples(
                            allUploadSamples,
                            ExtendedTestStats.MAX_GRAPH_POINTS,
                        )
                        _uploadSamples.value = finalUploadGraph

                        val uploadSummary = ExtendedTestStats.computePhaseSummaryExcludingWarmup(
                            allSamples = allUploadSamples,
                            warmUpSeconds = ExtendedTestStats.WARMUP_SECONDS,
                        )
                        if (finalUploadGraph.isEmpty() || uploadSummary == null) {
                            throw IOException("No upload throughput samples collected")
                        }

                        _uiState.update { state ->
                            state.copy(
                                stage = TestStage.COMPLETED,
                                isRunning = false,
                                isWarmingUp = false,
                                elapsedSeconds = durationSeconds,
                                liveSpeedMbps = uploadSummary.averageMbps,
                                downloadSamples = finalDownloadGraph,
                                uploadSamples = finalUploadGraph,
                                downloadSummary = downloadSummary,
                                uploadSummary = uploadSummary,
                                wasCancelled = false,
                                error = null,
                            )
                        }
                    }

                    monitorJob.start()
                    try {
                        executionJob.join()
                    } finally {
                        monitorJob.cancel()
                    }
                }

                val interruptedError = networkInterruption.get()
                if (interruptedError != null) {
                    flushPartialSamples(allDownloadSamples, allUploadSamples)
                    failWithError(interruptedError)
                }
            } catch (ce: CancellationException) {
                val interruptedError = networkInterruption.get()
                flushPartialSamples(allDownloadSamples, allUploadSamples)
                if (interruptedError != null) {
                    failWithError(interruptedError)
                } else {
                    throw ce
                }
            } catch (t: Throwable) {
                flushPartialSamples(allDownloadSamples, allUploadSamples)
                val interruptedError = networkInterruption.get()
                failWithError(interruptedError ?: mapThrowable(t))
            } finally {
                activeJob = null
            }
        }
    }

    private fun flushPartialSamples(
        allDownloadSamples: List<SpeedSample>,
        allUploadSamples: List<SpeedSample>,
    ) {
        if (allDownloadSamples.isNotEmpty()) {
            _downloadSamples.value = ExtendedTestStats.downsampleSamples(
                allDownloadSamples,
                ExtendedTestStats.MAX_GRAPH_POINTS,
            )
        }
        if (allUploadSamples.isNotEmpty()) {
            _uploadSamples.value = ExtendedTestStats.downsampleSamples(
                allUploadSamples,
                ExtendedTestStats.MAX_GRAPH_POINTS,
            )
        }
    }

    private fun failWithError(error: SpeedTestError) {
        // Keep partial graphs and any completed phase summary; never mark COMPLETED on error.
        val keptDownload = _downloadSamples.value
        val keptUpload = _uploadSamples.value
        _uiState.update { state ->
            state.copy(
                stage = TestStage.IDLE,
                isRunning = false,
                isWarmingUp = false,
                liveSpeedMbps = 0.0,
                downloadSamples = keptDownload,
                uploadSamples = keptUpload,
                wasCancelled = false,
                error = error,
            )
        }
    }

    private fun mapThrowable(t: Throwable): SpeedTestError = when (t) {
        is SocketTimeoutException -> SpeedTestError.Timeout
        is UnknownHostException -> SpeedTestError.ServerUnreachable
        is ServerHttpException, is ServerNoDataTimeoutException -> SpeedTestError.ServerUnreachable
        is IOException -> SpeedTestError.ServerUnreachable
        else -> SpeedTestError.Unknown
    }

    override fun onCleared() {
        activeJob?.cancel()
        activeJob = null
        networkMonitor.stopObserving()
        throughputClient.dispatcher.cancelAll()
        throughputClient.connectionPool.evictAll()
        super.onCleared()
    }

    companion object {
        // ~8 Hz UI emission throttle (within 5-10 samples/sec) for smooth, jitter-free graphing.
        private const val EMIT_THROTTLE_NANOS = 125_000_000L

        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val appContext = context.applicationContext
                val container = AppContainer.getInstance(appContext)
                val throughputClient = SpeedTestClientFactory.createThroughputClient()
                return ExtendedTestViewModel(
                    downloadEngine = DownloadEngine(throughputClient),
                    uploadEngine = UploadEngine(throughputClient),
                    networkMonitor = NetworkMonitor(appContext),
                    historyRepository = container.historyRepository,
                    settingsRepository = container.settingsRepository,
                    serverRepository = container.serverRepository,
                    throughputClient = throughputClient,
                ) as T
            }
        }
    }
}
