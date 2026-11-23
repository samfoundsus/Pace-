package com.example.engine.upload

import com.example.data.constants.SpeedTestConstants
import com.example.data.models.SpeedSample
import com.example.engine.latency.ServerHttpException
import com.example.engine.speedtest.RollingSpeedTracker
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

    data class Completed(val finalMbps: Double?) : UploadEvent
}

private class UploadCapReachedException : IOException("Upload duration cap reached")

/**
 * Multi-stream HTTP upload throughput engine.
 * Runs 4 parallel streams capped at 10s, discards the first 1.5s as warm-up,
 * generates non-compressible payload in memory, and counts only completed flushed bytes.
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
        streamCount: Int = SpeedTestConstants.UPLOAD_STREAM_COUNT,
        durationMs: Long = SpeedTestConstants.UPLOAD_DURATION_MS,
        warmUpMs: Long = SpeedTestConstants.UPLOAD_WARMUP_MS,
        sampleIntervalMs: Long = SpeedTestConstants.SAMPLE_INTERVAL_MS,
    ): Flow<UploadEvent> = channelFlow {
        val activeCalls = ConcurrentHashMap.newKeySet<Call>()
        val isCapActive = AtomicBoolean(true)
        val totalCompletedBytes = AtomicLong(0L)
        val measuredCompletedBytes = AtomicLong(0L)
        val firstError = AtomicReference<Throwable?>(null)

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
                                .url(SpeedTestConstants.uploadUrl(System.nanoTime() + streamIndex))
                                .cacheControl(noStoreCacheControl)
                                .post(body)
                                .build()

                            val call = client.newCall(request)
                            activeCalls.add(call)
                            val requestStartNanos = System.nanoTime()

                            try {
                                call.execute().use { response ->
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
                                // Normal 10-second cap exit; only already-flushed slices were counted
                                break
                            } catch (e: IOException) {
                                if (isActive && isCapActive.get() && System.nanoTime() < testEndNanos) {
                                    // Roll back unconfirmed bytes from a failed request
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

                val capWatcher = launch(Dispatchers.IO) {
                    val remainingMs = ((testEndNanos - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)
                    delay(remainingMs)
                    isCapActive.set(false)
                    activeCalls.forEach { it.cancel() }
                }

                workerJobs.awaitAll()
                capWatcher.cancelAndJoin()
                samplerJob.cancelAndJoin()
            }

            val actualEndNanos = System.nanoTime().coerceAtMost(testEndNanos)
            val finalTotalBytes = totalCompletedBytes.get().coerceAtLeast(0L)
            val postWarmupBytes = measuredCompletedBytes.get().coerceAtLeast(0L)
            val error = firstError.get()

            if (postWarmupBytes <= 0L && error != null) {
                throw error
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
            send(UploadEvent.Completed(finalMbps = finalMbps))
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
