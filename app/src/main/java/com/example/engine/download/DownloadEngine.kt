package com.example.engine.download

import com.example.data.constants.SpeedTestConstants
import com.example.data.models.SpeedSample
import com.example.data.server.CloudflareColoMapper
import com.example.data.server.ServerKind
import com.example.data.server.ServerProfile
import com.example.engine.latency.ServerHttpException
import com.example.engine.speedtest.RollingSpeedTracker
import com.example.engine.speedtest.ServerNoDataTimeoutException
import com.example.engine.speedtest.SpeedMath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

sealed interface DownloadEvent {
    data class Progress(
        val smoothedMbps: Double,
        val sample: SpeedSample,
        val isWarmingUp: Boolean,
        val progressFraction: Float,
    ) : DownloadEvent

    data class Completed(
        val finalMbps: Double?,
        val cfRayLocation: String? = null,
    ) : DownloadEvent
}

/**
 * Multi-stream HTTP download throughput engine.
 * Runs 6 parallel streams capped at durationMs, discards the first 1.5s as warm-up,
 * emits ~10 Hz smoothed rolling average updates, and uses [ServerProfile.downloadUrl].
 * If [noDataTimeoutMs] is set and zero bytes arrive in that window, throws [ServerNoDataTimeoutException].
 */
class DownloadEngine(
    private val client: OkHttpClient,
) {
    private val noStoreCacheControl = CacheControl.Builder()
        .noStore()
        .noCache()
        .build()

    fun measureDownload(
        server: ServerProfile,
        streamCount: Int = SpeedTestConstants.DOWNLOAD_STREAM_COUNT,
        durationMs: Long = SpeedTestConstants.DOWNLOAD_DURATION_MS,
        warmUpMs: Long = SpeedTestConstants.DOWNLOAD_WARMUP_MS,
        sampleIntervalMs: Long = SpeedTestConstants.SAMPLE_INTERVAL_MS,
        noDataTimeoutMs: Long? = null,
    ): Flow<DownloadEvent> = channelFlow {
        val activeCalls = ConcurrentHashMap.newKeySet<Call>()
        val isCapActive = AtomicBoolean(true)
        val noDataTimedOut = AtomicBoolean(false)
        val totalBytes = AtomicLong(0L)
        val measuredBytes = AtomicLong(0L)
        val firstError = AtomicReference<Throwable?>(null)
        val discoveredCfLocation = AtomicReference<String?>(null)

        val testStartNanos = System.nanoTime()
        val warmUpEndNanos = testStartNanos + warmUpMs * 1_000_000L
        val testEndNanos = testStartNanos + durationMs * 1_000_000L

        val tracker = RollingSpeedTracker(
            windowNanos = SpeedTestConstants.ROLLING_WINDOW_NANOS,
            emaAlpha = SpeedTestConstants.EMA_SMOOTHING_ALPHA,
        ).apply { reset(testStartNanos) }

        try {
            coroutineScope {
                val workerJobs = List(streamCount) { streamIndex ->
                    async(Dispatchers.IO) {
                        val buffer = ByteArray(SpeedTestConstants.DOWNLOAD_BUFFER_BYTES)
                        while (isActive && isCapActive.get() && System.nanoTime() < testEndNanos) {
                            val request = Request.Builder()
                                .url(
                                    server.downloadUrl(
                                        bytes = SpeedTestConstants.DOWNLOAD_CHUNK_BYTES,
                                        cacheBuster = System.nanoTime() + streamIndex,
                                    ),
                                )
                                .cacheControl(noStoreCacheControl)
                                .header("Accept-Encoding", "identity")
                                .get()
                                .build()

                            val call = client.newCall(request)
                            activeCalls.add(call)
                            try {
                                call.execute().use { response ->
                                    if (server.kind == ServerKind.CLOUDFLARE && discoveredCfLocation.get() == null) {
                                        CloudflareColoMapper.locationFromCfRay(response.header("cf-ray"))
                                            ?.let { discoveredCfLocation.compareAndSet(null, it) }
                                    }
                                    if (!response.isSuccessful) {
                                        throw ServerHttpException(response.code)
                                    }
                                    val stream = response.body?.byteStream()
                                        ?: throw IOException("Empty download response body")

                                    while (isActive && isCapActive.get()) {
                                        val bytesRead = stream.read(buffer)
                                        if (bytesRead <= 0) break

                                        val now = System.nanoTime()
                                        if (now >= testEndNanos) {
                                            isCapActive.set(false)
                                            break
                                        }

                                        val count = bytesRead.toLong()
                                        totalBytes.addAndGet(count)
                                        if (now >= warmUpEndNanos) {
                                            measuredBytes.addAndGet(count)
                                        }
                                    }
                                }
                            } catch (e: IOException) {
                                // Ignore expected socket closures caused by cap termination or cancellation
                                if (isActive && isCapActive.get() && System.nanoTime() < testEndNanos) {
                                    firstError.compareAndSet(null, e)
                                    break
                                }
                            } finally {
                                activeCalls.remove(call)
                            }
                        }
                    }
                }

                val samplerJob = launch(Dispatchers.IO) {
                    while (isActive) {
                        delay(sampleIntervalMs)
                        val now = System.nanoTime()
                        val clampedNow = now.coerceAtMost(testEndNanos)
                        val elapsedSec = ((clampedNow - testStartNanos) / 1_000_000_000.0).toFloat()
                        val fraction = (elapsedSec / (durationMs / 1000f)).coerceIn(0f, 1f)
                        val smoothed = tracker.recordAndGetSmoothedMbps(clampedNow, totalBytes.get())

                        send(
                            DownloadEvent.Progress(
                                smoothedMbps = smoothed,
                                sample = SpeedSample(elapsedSeconds = elapsedSec, speedMbps = smoothed),
                                isWarmingUp = clampedNow < warmUpEndNanos,
                                progressFraction = fraction,
                            ),
                        )

                        if (now >= testEndNanos || workerJobs.all { it.isCompleted }) {
                            break
                        }
                    }
                }

                val noDataWatcher = if (noDataTimeoutMs != null && noDataTimeoutMs > 0L) {
                    launch(Dispatchers.IO) {
                        delay(noDataTimeoutMs)
                        if (totalBytes.get() <= 0L) {
                            noDataTimedOut.set(true)
                            isCapActive.set(false)
                            activeCalls.forEach { it.cancel() }
                        }
                    }
                } else {
                    null
                }

                val capWatcher = launch(Dispatchers.IO) {
                    val remainingMs = ((testEndNanos - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)
                    delay(remainingMs)
                    isCapActive.set(false)
                    activeCalls.forEach { it.cancel() }
                }

                workerJobs.awaitAll()
                noDataWatcher?.cancelAndJoin()
                capWatcher.cancelAndJoin()
                samplerJob.cancelAndJoin()
            }

            if (noDataTimedOut.get()) {
                throw ServerNoDataTimeoutException("No download data received within ${noDataTimeoutMs}ms")
            }

            val actualEndNanos = System.nanoTime().coerceAtMost(testEndNanos)
            val finalTotalBytes = totalBytes.get()
            val postWarmupBytes = measuredBytes.get()
            val error = firstError.get()

            if (postWarmupBytes <= 0L && error != null) {
                throw error
            }
            if (finalTotalBytes <= 0L && noDataTimeoutMs != null) {
                throw ServerNoDataTimeoutException("Download returned zero bytes")
            }

            if (finalTotalBytes > 0L && actualEndNanos > testStartNanos) {
                val finalElapsedSec = ((actualEndNanos - testStartNanos) / 1_000_000_000.0).toFloat()
                val finalFraction = (finalElapsedSec / (durationMs / 1000f)).coerceIn(0f, 1f)
                val finalSmoothed = tracker.recordAndGetSmoothedMbps(actualEndNanos, finalTotalBytes)
                send(
                    DownloadEvent.Progress(
                        smoothedMbps = finalSmoothed,
                        sample = SpeedSample(elapsedSeconds = finalElapsedSec, speedMbps = finalSmoothed),
                        isWarmingUp = actualEndNanos < warmUpEndNanos,
                        progressFraction = finalFraction,
                    ),
                )
            }

            val postWarmupDurationNanos = actualEndNanos - warmUpEndNanos
            val finalMbps = SpeedMath.calculateMbps(postWarmupBytes, postWarmupDurationNanos)
            send(
                DownloadEvent.Completed(
                    finalMbps = finalMbps,
                    cfRayLocation = discoveredCfLocation.get(),
                ),
            )
        } catch (ce: CancellationException) {
            isCapActive.set(false)
            activeCalls.forEach { it.cancel() }
            throw ce
        } finally {
            isCapActive.set(false)
            activeCalls.forEach { it.cancel() }
            activeCalls.clear()
        }
    }.flowOn(Dispatchers.IO)
}
