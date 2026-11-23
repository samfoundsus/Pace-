package com.example.engine.speedtest

import com.example.data.constants.SpeedTestConstants
import com.example.data.models.ConnectionType
import com.example.data.models.ResultEvaluator
import com.example.data.models.SpeedTestError
import com.example.data.models.SpeedTestProgress
import com.example.data.models.TestResult
import com.example.data.models.TestStage
import com.example.engine.download.DownloadEngine
import com.example.engine.download.DownloadEvent
import com.example.engine.latency.LatencyEngine
import com.example.engine.latency.ServerHttpException
import com.example.engine.upload.UploadEngine
import com.example.engine.upload.UploadEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
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
 * while monitoring connectivity for network loss or network switches.
 */
class SpeedTestEngine(
    private val latencyEngine: LatencyEngine,
    private val loadedLatencyEngine: LatencyEngine,
    private val downloadEngine: DownloadEngine,
    private val uploadEngine: UploadEngine,
    private val networkMonitor: NetworkMonitor,
) {
    fun runSpeedTest(liteMode: Boolean = false): Flow<SpeedTestProgress> = channelFlow {
        val initialNetwork = networkMonitor.currentSnapshot()
        if (initialNetwork.connectionType == ConnectionType.NONE) {
            send(SpeedTestProgress.Failed(SpeedTestError.NoInternet))
            return@channelFlow
        }

        val downloadDurationMs = if (liteMode) {
            SpeedTestConstants.DOWNLOAD_LITE_DURATION_MS
        } else {
            SpeedTestConstants.DOWNLOAD_DURATION_MS
        }
        val uploadDurationMs = if (liteMode) {
            SpeedTestConstants.UPLOAD_LITE_DURATION_MS
        } else {
            SpeedTestConstants.UPLOAD_DURATION_MS
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
                    // Stage 1: Unloaded Ping & Jitter
                    send(SpeedTestProgress.StageStarted(TestStage.PING))
                    val latency = latencyEngine.measureUnloadedLatency { completed, total, latest, median ->
                        send(
                            SpeedTestProgress.PingSampled(
                                completedCount = completed,
                                totalCount = total,
                                latestPingMs = latest,
                                currentMedianPingMs = median,
                            ),
                        )
                    }
                    val finalJitterMs = SpeedMath.calculateJitter(latency.samplesMs)
                    send(SpeedTestProgress.PingCompleted(pingMs = latency.pingMs, jitterMs = finalJitterMs))

                    // Stage 2: Download Throughput + Concurrent Loaded Latency (500 ms probes on separate connection)
                    send(SpeedTestProgress.StageStarted(TestStage.DOWNLOAD))
                    var downloadMbps: Double? = null
                    val downloadLatencySamples = Collections.synchronizedList(mutableListOf<Double>())
                    val downloadProbeJob = launch(Dispatchers.IO) {
                        loadedLatencyEngine.probeFlow(SpeedTestConstants.LOADED_LATENCY_INTERVAL_MS)
                            .collect { rttMs ->
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
                        downloadEngine.measureDownload(durationMs = downloadDurationMs).collect { event ->
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
                                }
                            }
                        }
                    } finally {
                        downloadProbeJob.cancelAndJoin()
                    }
                    val downloadLoadedLatency = medianIfEnoughSamples(downloadLatencySamples)
                    send(
                        SpeedTestProgress.DownloadCompleted(
                            downloadMbps = downloadMbps,
                            loadedLatencyMs = downloadLoadedLatency,
                        ),
                    )

                    // Stage 3: Upload Throughput + Concurrent Loaded Latency (500 ms probes on separate connection)
                    send(SpeedTestProgress.StageStarted(TestStage.UPLOAD))
                    var uploadMbps: Double? = null
                    val uploadLatencySamples = Collections.synchronizedList(mutableListOf<Double>())
                    val uploadProbeJob = launch(Dispatchers.IO) {
                        loadedLatencyEngine.probeFlow(SpeedTestConstants.LOADED_LATENCY_INTERVAL_MS)
                            .collect { rttMs ->
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
                        uploadEngine.measureUpload(durationMs = uploadDurationMs).collect { event ->
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
                                }
                            }
                        }
                    } finally {
                        uploadProbeJob.cancelAndJoin()
                    }
                    val uploadLoadedLatency = medianIfEnoughSamples(uploadLatencySamples)
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
            is ConnectException, is SSLException, is ServerHttpException -> SpeedTestError.ServerUnreachable
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
