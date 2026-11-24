package com.example.engine.upload

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
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

sealed interface UploadEvent {
    data class Progress(
        val smoothedMbps: Double,
        val sample: SpeedSample,
        val isWarmingUp: Boolean,
        val progressFraction: Float,
    ) : UploadEvent

    data class Completed(
        val finalMbps: Double?,
        val cfRayLocation: String? = null,
    ) : UploadEvent
}

private class UploadCapReachedException : IOException("Upload duration cap reached")

/**
 * Multi-stream HTTP upload throughput engine.
 * Runs 4 parallel streams capped at durationMs, discards the first 1.5s as warm-up,
 * generates non-compressible payload in memory, counts only completed flushed bytes,
 * and uses [ServerProfile.uploadUrl].
 * If [noDataTimeoutMs] is set and zero bytes are confirmed within that window, throws [ServerNoDataTimeoutException].
 */
class UploadEngine(
    private val client: OkHttpClient,
) {
    private val octetStreamMediaType: MediaType = "application/octet-stream".toMediaType()
    private val noStoreCacheControl = CacheControl.Builder()
        .noStore()
        .noCache()
        .build()

    // Single reusable 64 KB deterministic pseudo-random buffer in memory (non-compressible, tiny heap footprint)
    private val payloadChunk: ByteArray = Random(42).nextBytes(SpeedTestConstants.UPLOAD_PAYLOAD_BUFFER_BYTES)

    fun measureUpload(
        server: ServerProfile,
        streamCount: Int = SpeedTestConstants.UPLOAD_STREAM_COUNT,
        durationMs: Long = SpeedTestConstants.UPLOAD_DURATION_MS,
        warmUpMs: Long = SpeedTestConstants.UPLOAD_WARMUP_MS,
        sampleIntervalMs: Long = SpeedTestConstants.SAMPLE_INTERVAL_MS,
        noDataTimeoutMs: Long? = null,
    ): Flow<UploadEvent> = channelFlow {
        val activeCalls = ConcurrentHashMap.newKeySet<Call>()
        val isCapActive = AtomicBoolean(true)
        val noDataTimedOut = AtomicBoolean(false)
        val totalCompletedBytes = AtomicLong(0L)
        val measuredCompletedBytes = AtomicLong(0L)
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
                        var currentChunkBytes = SpeedTestConstants.UPLOAD_INITIAL_CHUNK_BYTES

                        while (isActive && isCapActive.get() && System.nanoTime() < testEndNanos) {
                            var requestTotalWritten = 0L
                            var requestPostWarmupWritten = 0L

                            val body = CountingUploadRequestBody(
                                totalBytesToSend = currentChunkBytes,
                                payloadChunk = payloadChunk,
                                mediaType = octetStreamMediaType,
                                shouldStop = {
                                    !isActive || !isCapActive.get() || System.nanoTime() >= testEndNanos
                                },
                                onSliceFlushed = { bytesWritten, nowNanos ->
                                    requestTotalWritten += bytesWritten
                                    totalCompletedBytes.addAndGet(bytesWritten)
                                    if (nowNanos >= warmUpEndNanos && nowNanos <= testEndNanos) {
                                        requestPostWarmupWritten += bytesWritten
                                        measuredCompletedBytes.addAndGet(bytesWritten)
                                    }
                                },
                            )

                            val request = Request.Builder()
                                .url(server.uploadUrl(System.nanoTime() + streamIndex))
                                .cacheControl(noStoreCacheControl)
                                .post(body)
                                .build()

                            val call = client.newCall(request)
                            activeCalls.add(call)
                            val requestStartNanos = System.nanoTime()

                            try {
                                call.execute().use { response ->
                                    if (server.kind == ServerKind.CLOUDFLARE && discoveredCfLocation.get() == null) {
                                        CloudflareColoMapper.locationFromCfRay(response.header("cf-ray"))
                                            ?.let { discoveredCfLocation.compareAndSet(null, it) }
                                    }
                                    if (!response.isSuccessful) {
                                        throw ServerHttpException(response.code)
                                    }
                                    response.body?.close()
                                }
                                // Adapt chunk size up on fast connections to keep streams saturated
                                val requestDurationMs = (System.nanoTime() - requestStartNanos) / 1_000_000L
                                if (requestDurationMs < 400L && currentChunkBytes < SpeedTestConstants.UPLOAD_MAX_CHUNK_BYTES) {
                                    currentChunkBytes = (currentChunkBytes * 2L)
                                        .coerceAtMost(SpeedTestConstants.UPLOAD_MAX_CHUNK_BYTES)
                                }
                            } catch (_: UploadCapReachedException) {
                                break
                            } catch (e: IOException) {
                                if (isActive && isCapActive.get() && System.nanoTime() < testEndNanos) {
                                    if (requestTotalWritten > 0L) {
                                        totalCompletedBytes.addAndGet(-requestTotalWritten)
                                    }
                                    if (requestPostWarmupWritten > 0L) {
                                        measuredCompletedBytes.addAndGet(-requestPostWarmupWritten)
                                    }
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
                        val smoothed = tracker.recordAndGetSmoothedMbps(
                            clampedNow,
                            totalCompletedBytes.get().coerceAtLeast(0L),
                        )

                        send(
                            UploadEvent.Progress(
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
                        if (totalCompletedBytes.get() <= 0L) {
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
                throw ServerNoDataTimeoutException("No upload data transferred within ${noDataTimeoutMs}ms")
            }

            val actualEndNanos = System.nanoTime().coerceAtMost(testEndNanos)
            val finalTotalBytes = totalCompletedBytes.get().coerceAtLeast(0L)
            val postWarmupBytes = measuredCompletedBytes.get().coerceAtLeast(0L)
            val error = firstError.get()

            if (postWarmupBytes <= 0L && error != null) {
                throw error
            }
            if (finalTotalBytes <= 0L && noDataTimeoutMs != null) {
                throw ServerNoDataTimeoutException("Upload transferred zero bytes")
            }

            if (finalTotalBytes > 0L && actualEndNanos > testStartNanos) {
                val finalElapsedSec = ((actualEndNanos - testStartNanos) / 1_000_000_000.0).toFloat()
                val finalFraction = (finalElapsedSec / (durationMs / 1000f)).coerceIn(0f, 1f)
                val finalSmoothed = tracker.recordAndGetSmoothedMbps(actualEndNanos, finalTotalBytes)
                send(
                    UploadEvent.Progress(
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
                UploadEvent.Completed(
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

/**
 * Streams an in-memory generated payload in small flushed slices, counting bytes strictly after
 * each slice completes writing to the sink.
 */
private class CountingUploadRequestBody(
    private val totalBytesToSend: Long,
    private val payloadChunk: ByteArray,
    private val mediaType: MediaType,
    private val shouldStop: () -> Boolean,
    private val onSliceFlushed: (bytesWritten: Long, timestampNanos: Long) -> Unit,
) : RequestBody() {

    override fun contentType(): MediaType = mediaType

    override fun contentLength(): Long = totalBytesToSend

    override fun writeTo(sink: BufferedSink) {
        var remaining = totalBytesToSend
        var offset = 0
        val sliceLimit = SpeedTestConstants.UPLOAD_WRITE_SLICE_BYTES

        while (remaining > 0L) {
            if (shouldStop()) {
                throw UploadCapReachedException()
            }
            val chunkAvail = payloadChunk.size - offset
            val toWrite = minOf(remaining, sliceLimit.toLong(), chunkAvail.toLong()).toInt()
            sink.write(payloadChunk, offset, toWrite)
            sink.flush()

            val now = System.nanoTime()
            onSliceFlushed(toWrite.toLong(), now)

            remaining -= toWrite
            offset = (offset + toWrite) % payloadChunk.size
        }
    }
}
