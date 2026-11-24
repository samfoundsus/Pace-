package com.example.engine.speedtest

import com.example.data.constants.SpeedTestConstants
import com.example.data.models.ConnectionType
import com.example.data.models.ResultEvaluator
import com.example.data.models.SpeedTestError
import com.example.data.models.SpeedTestProgress
import com.example.data.models.TestResult
import com.example.data.models.TestStage
import com.example.data.server.ServerKind
import com.example.data.server.ServerProfile
import com.example.engine.download.DownloadEngine
import com.example.engine.download.DownloadEvent
import com.example.engine.latency.LatencyEngine
import com.example.engine.latency.LatencyMeasurement
import com.example.engine.latency.ServerHttpException
import com.example.engine.upload.UploadEngine
import com.example.engine.upload.UploadEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException

/**
 * Coordinates Latency -> Download (+ loaded latency) -> Upload (+ loaded latency) via Flow,
 * using the active [ServerProfile] for every phase and automatically falling back to Cloudflare
 * if a chosen non-Cloudflare server is unreachable or returns no data within the first 5 seconds.
 */
class SpeedTestEngine(
    private val latencyEngine: LatencyEngine,
    private val loadedLatencyEngine: LatencyEngine,
    private val downloadEngine: DownloadEngine,
    private val uploadEngine: UploadEngine,
    private val networkMonitor: NetworkMonitor,
) {
    fun runSpeedTest(
        server: ServerProfile,
        liteMode: Boolean = false,
        noDataFallbackTimeoutMs: Long = SpeedTestConstants.NO_DATA_FALLBACK_TIMEOUT_MS,
    ): Flow<SpeedTestProgress> = runSpeedTestInternal(
        server = server,
        downloadDurationMs = if (liteMode) SpeedTestConstants.DOWNLOAD_LITE_DURATION_MS else SpeedTestConstants.DOWNLOAD_DURATION_MS,
        uploadDurationMs = if (liteMode) SpeedTestConstants.UPLOAD_LITE_DURATION_MS else SpeedTestConstants.UPLOAD_DURATION_MS,
        warmUpMs = SpeedTestConstants.DOWNLOAD_WARMUP_MS,
        noDataFallbackTimeoutMs = noDataFallbackTimeoutMs,
    )

    internal fun runSpeedTestInternal(
        server: ServerProfile,
        downloadDurationMs: Long,
        uploadDurationMs: Long,
        warmUpMs: Long,
        noDataFallbackTimeoutMs: Long,
    ): Flow<SpeedTestProgress> = channelFlow {
        val initialNetwork = networkMonitor.currentSnapshot()
        if (initialNetwork.connectionType == ConnectionType.NONE) {
            send(SpeedTestProgress.Failed(SpeedTestError.NoInternet))
            return@channelFlow
        }

        val networkInterruption = AtomicReference<SpeedTestError?>(null)

        try {
            coroutineScope {
                var executionJob: Job? = null

                val watcherJob = launch(
                    context = Dispatchers.IO,
                    start = CoroutineStart.LAZY,
                ) {
                    networkMonitor.networkState.collect { snapshot ->
                        val interruption = when {
                            snapshot.connectionType == ConnectionType.NONE -> SpeedTestError.NetworkLost
                            snapshot.connectionType != initialNetwork.connectionType -> SpeedTestError.NetworkChanged
                            initialNetwork.networkHandle != null &&
                                snapshot.networkHandle != null &&
                                snapshot.networkHandle != initialNetwork.networkHandle -> SpeedTestError.NetworkChanged
                            else -> null
                        }
                        if (interruption != null) {
                            if (networkInterruption.compareAndSet(null, interruption)) {
                                executionJob?.cancel(CancellationException("Network interrupted"))
                            }
                        }
                    }
                }

                executionJob = launch(Dispatchers.IO) {
                    var activeServer = server
                    var discoveredCfLocation: String? = if (
                        server.kind == ServerKind.CLOUDFLARE && server.location.isNotBlank()
                    ) {
                        server.location
                    } else {
                        null
                    }

                    fun effectiveServer(): ServerProfile =
                        if (activeServer.kind == ServerKind.CLOUDFLARE && !discoveredCfLocation.isNullOrBlank()) {
                            activeServer.copy(location = discoveredCfLocation!!)
                        } else {
                            activeServer
                        }

                    suspend fun captureCfLocation(loc: String?) {
                        val clean = loc?.trim()?.takeIf { it.isNotEmpty() } ?: return
                        if (activeServer.kind == ServerKind.CLOUDFLARE && discoveredCfLocation != clean) {
                            discoveredCfLocation = clean
                            send(SpeedTestProgress.ServerActive(effectiveServer()))
                        }
                    }

                    fun canFallbackToCloudflare(): Boolean =
                        activeServer.kind != ServerKind.CLOUDFLARE &&
                            networkInterruption.get() == null &&
                            networkMonitor.currentSnapshot().connectionType != ConnectionType.NONE

                    suspend fun switchToCloudflare(): ServerProfile {
                        val previousName = activeServer.name
                        activeServer = ServerProfile.CLOUDFLARE
                        discoveredCfLocation = null
                        send(
                            SpeedTestProgress.ServerFallback(
                                previousServerName = previousName,
                                fallbackServer = activeServer,
                            ),
                        )
                        send(SpeedTestProgress.ServerActive(activeServer))
                        return activeServer
                    }

                    send(SpeedTestProgress.ServerActive(effectiveServer()))

                    // Stage 1: Unloaded Ping & Jitter
                    val latency = runPingStageWithFallback(
                        initialServer = activeServer,
                        canFallback = ::canFallbackToCloudflare,
                        onSwitchToCloudflare = ::switchToCloudflare,
                    )
                    captureCfLocation(latency.cfRayLocation)
                    val finalJitterMs = SpeedMath.calculateJitter(latency.samplesMs)
                    send(SpeedTestProgress.PingCompleted(pingMs = latency.pingMs, jitterMs = finalJitterMs))

                    // Stage 2: Download Throughput + Concurrent Loaded Latency
                    val (downloadMbps, downloadLoadedLatency, dlCfLoc) = runDownloadStageWithFallback(
                        getActiveServer = { activeServer },
                        downloadDurationMs = downloadDurationMs,
                        warmUpMs = warmUpMs,
                        noDataFallbackTimeoutMs = noDataFallbackTimeoutMs,
                        canFallback = ::canFallbackToCloudflare,
                        onSwitchToCloudflare = ::switchToCloudflare,
                    )
                    captureCfLocation(dlCfLoc)
                    send(
                        SpeedTestProgress.DownloadCompleted(
                            downloadMbps = downloadMbps,
                            loadedLatencyMs = downloadLoadedLatency,
                        ),
                    )

                    // Stage 3: Upload Throughput + Concurrent Loaded Latency
                    val (uploadMbps, uploadLoadedLatency, ulCfLoc) = runUploadStageWithFallback(
                        getActiveServer = { activeServer },
                        uploadDurationMs = uploadDurationMs,
                        warmUpMs = warmUpMs,
                        noDataFallbackTimeoutMs = noDataFallbackTimeoutMs,
                        canFallback = ::canFallbackToCloudflare,
                        onSwitchToCloudflare = ::switchToCloudflare,
                    )
                    captureCfLocation(ulCfLoc)
                    send(
                        SpeedTestProgress.UploadCompleted(
                            uploadMbps = uploadMbps,
                            loadedLatencyMs = uploadLoadedLatency,
                        ),
                    )

                    // Final guard: never emit a completed result if network changed right at the end
                    if (networkInterruption.get() == null) {
                        val latestNetwork = networkMonitor.currentSnapshot()
                        val resolvedBand = (latestNetwork.wifiBand ?: initialNetwork.wifiBand)?.label
                        val resolvedCellularTag = latestNetwork.cellularNetworkTag ?: initialNetwork.cellularNetworkTag
                        val resolvedNetworkName = latestNetwork.networkName ?: initialNetwork.networkName
                        val bufferbloatGrade = ResultEvaluator.computeBufferbloatGrade(
                            idlePingMs = latency.pingMs,
                            downloadLoadedMs = downloadLoadedLatency,
                            uploadLoadedMs = uploadLoadedLatency,
                        )
                        val finalServer = effectiveServer()
                        val result = TestResult(
                            timestamp = System.currentTimeMillis(),
                            connectionType = initialNetwork.connectionType,
                            ping = latency.pingMs,
                            jitter = finalJitterMs,
                            download = downloadMbps,
                            upload = uploadMbps,
                            downloadLoadedLatency = downloadLoadedLatency,
                            uploadLoadedLatency = uploadLoadedLatency,
                            bufferbloatGrade = bufferbloatGrade,
                            networkName = resolvedNetworkName,
                            cellularNetworkTag = resolvedCellularTag,
                            deviceModel = initialNetwork.deviceModel.takeIf { it.isNotBlank() },
                            wifiBand = resolvedBand,
                            serverName = finalServer.name,
                            serverLocation = finalServer.location.ifBlank { null },
                        )
                        send(SpeedTestProgress.Completed(result))
                    }
                }

                watcherJob.start()
                executionJob.join()
                watcherJob.cancel()
            }

            val interruption = networkInterruption.get()
            if (interruption != null) {
                send(SpeedTestProgress.Failed(interruption))
            }
        } catch (ce: CancellationException) {
            val interruption = networkInterruption.get()
            if (interruption != null) {
                send(SpeedTestProgress.Failed(interruption))
            } else {
                // External user cancellation: exit silently without emitting an incomplete result
                throw ce
            }
        } catch (t: Throwable) {
            val interruption = networkInterruption.get()
            val mappedError = interruption ?: mapThrowableToError(t)
            send(SpeedTestProgress.Failed(mappedError))
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun ProducerScope<SpeedTestProgress>.runPingStageWithFallback(
        initialServer: ServerProfile,
        canFallback: () -> Boolean,
        onSwitchToCloudflare: suspend () -> ServerProfile,
    ): LatencyMeasurement {
        send(SpeedTestProgress.StageStarted(TestStage.PING))
        return try {
            latencyEngine.measureUnloadedLatency(server = initialServer) { completed, total, latest, median ->
                send(
                    SpeedTestProgress.PingSampled(
                        completedCount = completed,
                        totalCount = total,
                        latestPingMs = latest,
                        currentMedianPingMs = median,
                    ),
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: IOException) {
            if (!canFallback()) throw e
            val fallbackServer = onSwitchToCloudflare()
            send(SpeedTestProgress.StageStarted(TestStage.PING))
            latencyEngine.measureUnloadedLatency(server = fallbackServer) { completed, total, latest, median ->
                send(
                    SpeedTestProgress.PingSampled(
                        completedCount = completed,
                        totalCount = total,
                        latestPingMs = latest,
                        currentMedianPingMs = median,
                    ),
                )
            }
        }
    }

    private suspend fun ProducerScope<SpeedTestProgress>.runDownloadStageWithFallback(
        getActiveServer: () -> ServerProfile,
        downloadDurationMs: Long,
        warmUpMs: Long,
        noDataFallbackTimeoutMs: Long,
        canFallback: () -> Boolean,
        onSwitchToCloudflare: suspend () -> ServerProfile,
    ): Triple<Double?, Double?, String?> {
        val currentServer = getActiveServer()
        return try {
            executeDownloadPhase(
                server = currentServer,
                durationMs = downloadDurationMs,
                warmUpMs = warmUpMs,
                noDataTimeoutMs = if (currentServer.kind != ServerKind.CLOUDFLARE) noDataFallbackTimeoutMs else null,
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: IOException) {
            if (!canFallback()) throw e
            val fallbackServer = onSwitchToCloudflare()
            executeDownloadPhase(
                server = fallbackServer,
                durationMs = downloadDurationMs,
                warmUpMs = warmUpMs,
                noDataTimeoutMs = null,
            )
        }
    }

    private suspend fun ProducerScope<SpeedTestProgress>.executeDownloadPhase(
        server: ServerProfile,
        durationMs: Long,
        warmUpMs: Long,
        noDataTimeoutMs: Long?,
    ): Triple<Double?, Double?, String?> {
        send(SpeedTestProgress.StageStarted(TestStage.DOWNLOAD))
        var downloadMbps: Double? = null
        var cfRayLocation: String? = null
        val downloadLatencySamples = Collections.synchronizedList(mutableListOf<Double>())
        val downloadProbeJob = launch(Dispatchers.IO) {
            loadedLatencyEngine.probeFlow(
                server = server,
                intervalMs = SpeedTestConstants.LOADED_LATENCY_INTERVAL_MS,
                onCfRayLocation = { loc -> if (cfRayLocation == null) cfRayLocation = loc },
            ).collect { rttMs ->
                downloadLatencySamples.add(rttMs)
                medianIfEnoughSamples(downloadLatencySamples)?.let { median ->
                    trySend(
                        SpeedTestProgress.LoadedLatencySampled(
                            stage = TestStage.DOWNLOAD,
                            medianLatencyMs = median,
                        ),
                    )
                }
            }
        }
        try {
            downloadEngine.measureDownload(
                server = server,
                durationMs = durationMs,
                warmUpMs = warmUpMs,
                noDataTimeoutMs = noDataTimeoutMs,
            ).collect { event ->
                when (event) {
                    is DownloadEvent.Progress -> {
                        send(
                            SpeedTestProgress.ThroughputUpdate(
                                stage = TestStage.DOWNLOAD,
                                currentSpeedMbps = event.smoothedMbps,
                                sample = event.sample,
                                isWarmingUp = event.isWarmingUp,
                                progressFraction = event.progressFraction,
                            ),
                        )
                    }
                    is DownloadEvent.Completed -> {
                        downloadMbps = event.finalMbps
                        if (event.cfRayLocation != null) {
                            cfRayLocation = event.cfRayLocation
                        }
                    }
                }
            }
        } finally {
            downloadProbeJob.cancelAndJoin()
        }
        val downloadLoadedLatency = medianIfEnoughSamples(downloadLatencySamples)
        return Triple(downloadMbps, downloadLoadedLatency, cfRayLocation)
    }

    private suspend fun ProducerScope<SpeedTestProgress>.runUploadStageWithFallback(
        getActiveServer: () -> ServerProfile,
        uploadDurationMs: Long,
        warmUpMs: Long,
        noDataFallbackTimeoutMs: Long,
        canFallback: () -> Boolean,
        onSwitchToCloudflare: suspend () -> ServerProfile,
    ): Triple<Double?, Double?, String?> {
        val currentServer = getActiveServer()
        return try {
            executeUploadPhase(
                server = currentServer,
                durationMs = uploadDurationMs,
                warmUpMs = warmUpMs,
                noDataTimeoutMs = if (currentServer.kind != ServerKind.CLOUDFLARE) noDataFallbackTimeoutMs else null,
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: IOException) {
            if (!canFallback()) throw e
            val fallbackServer = onSwitchToCloudflare()
            executeUploadPhase(
                server = fallbackServer,
                durationMs = uploadDurationMs,
                warmUpMs = warmUpMs,
                noDataTimeoutMs = null,
            )
        }
    }

    private suspend fun ProducerScope<SpeedTestProgress>.executeUploadPhase(
        server: ServerProfile,
        durationMs: Long,
        warmUpMs: Long,
        noDataTimeoutMs: Long?,
    ): Triple<Double?, Double?, String?> {
        send(SpeedTestProgress.StageStarted(TestStage.UPLOAD))
        var uploadMbps: Double? = null
        var cfRayLocation: String? = null
        val uploadLatencySamples = Collections.synchronizedList(mutableListOf<Double>())
        val uploadProbeJob = launch(Dispatchers.IO) {
            loadedLatencyEngine.probeFlow(
                server = server,
                intervalMs = SpeedTestConstants.LOADED_LATENCY_INTERVAL_MS,
                onCfRayLocation = { loc -> if (cfRayLocation == null) cfRayLocation = loc },
            ).collect { rttMs ->
                uploadLatencySamples.add(rttMs)
                medianIfEnoughSamples(uploadLatencySamples)?.let { median ->
                    trySend(
                        SpeedTestProgress.LoadedLatencySampled(
                            stage = TestStage.UPLOAD,
                            medianLatencyMs = median,
                        ),
                    )
                }
            }
        }
        try {
            uploadEngine.measureUpload(
                server = server,
                durationMs = durationMs,
                warmUpMs = warmUpMs,
                noDataTimeoutMs = noDataTimeoutMs,
            ).collect { event ->
                when (event) {
                    is UploadEvent.Progress -> {
                        send(
                            SpeedTestProgress.ThroughputUpdate(
                                stage = TestStage.UPLOAD,
                                currentSpeedMbps = event.smoothedMbps,
                                sample = event.sample,
                                isWarmingUp = event.isWarmingUp,
                                progressFraction = event.progressFraction,
                            ),
                        )
                    }
                    is UploadEvent.Completed -> {
                        uploadMbps = event.finalMbps
                        if (event.cfRayLocation != null) {
                            cfRayLocation = event.cfRayLocation
                        }
                    }
                }
            }
        } finally {
            uploadProbeJob.cancelAndJoin()
        }
        val uploadLoadedLatency = medianIfEnoughSamples(uploadLatencySamples)
        return Triple(uploadMbps, uploadLoadedLatency, cfRayLocation)
    }

    private fun medianIfEnoughSamples(samples: List<Double>): Double? {
        val snapshot = synchronized(samples) { samples.toList() }
        if (snapshot.size < SpeedTestConstants.MIN_LOADED_LATENCY_SAMPLES) return null
        return SpeedMath.calculateMedian(snapshot)
    }

    private fun mapThrowableToError(throwable: Throwable): SpeedTestError {
        if (networkMonitor.currentSnapshot().connectionType == ConnectionType.NONE) {
            return SpeedTestError.NoInternet
        }
        return when (throwable) {
            is SocketTimeoutException, is InterruptedIOException -> SpeedTestError.Timeout
            is UnknownHostException, is NoRouteToHostException -> {
                if (networkMonitor.currentSnapshot().connectionType == ConnectionType.NONE) {
                    SpeedTestError.NoInternet
                } else {
                    SpeedTestError.ServerUnreachable
                }
            }
            is ConnectException, is SSLException, is ServerHttpException, is ServerNoDataTimeoutException ->
                SpeedTestError.ServerUnreachable
            is IOException -> {
                val msg = throwable.message?.lowercase().orEmpty()
                when {
                    msg.contains("timeout") -> SpeedTestError.Timeout
                    else -> SpeedTestError.ServerUnreachable
                }
            }
            else -> SpeedTestError.Unknown
        }
    }
}
