package com.example.engine.latency

import com.example.data.constants.SpeedTestConstants
import com.example.engine.speedtest.SpeedMath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

data class LatencyMeasurement(
    val pingMs: Double?,
    val jitterMs: Double?,
    val samplesMs: List<Double>,
)

class ServerHttpException(val statusCode: Int) : IOException("Unexpected HTTP status $statusCode")

/**
 * Modular latency measurement engine.
 * - Reuses a single persistent connection per client (HTTP/2 with HTTP/1.1 fallback).
 * - Measures pure HTTP request-to-response RTT in Double milliseconds via OkHttp's EventListener
 *   (requestHeadersStart -> responseHeadersEnd) so DNS, TCP connect, IPv6 fallback, and TLS
 *   handshakes are never included in ping or jitter samples.
 * - Sends one throwaway warm-up request (never counted) before measuring idle RTT samples.
 */
class LatencyEngine internal constructor(
    private val client: OkHttpClient,
    private val singleProbeOverride: (suspend () -> Double)?,
    private val probeSpacingMs: Long = SpeedTestConstants.PING_PROBE_SPACING_MS,
) {
    constructor(client: OkHttpClient) : this(
        client = client,
        singleProbeOverride = null,
        probeSpacingMs = SpeedTestConstants.PING_PROBE_SPACING_MS,
    )

    private val noStoreCacheControl = CacheControl.Builder()
        .noStore()
        .noCache()
        .build()

    private class HttpRttEventListener : EventListener() {
        @Volatile
        var requestStartNanos: Long = 0L

        @Volatile
        var responseEndNanos: Long = 0L

        override fun requestHeadersStart(call: Call) {
            requestStartNanos = System.nanoTime()
        }

        override fun responseHeadersEnd(call: Call, response: Response) {
            responseEndNanos = System.nanoTime()
        }
    }

    /**
     * Executes a single lightweight HTTP RTT probe measured in Double milliseconds
     * (nanoseconds / 1_000_000.0). Uses OkHttp's EventListener to time strictly from
     * requestHeadersStart to responseHeadersEnd so connection setup (DNS, TCP, TLS) is excluded.
     */
    suspend fun measureSingleRttMs(
        callTimeoutMs: Long = SpeedTestConstants.PING_CALL_TIMEOUT_SECONDS * 1_000L,
    ): Double = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(SpeedTestConstants.pingUrl(System.nanoTime()))
            .cacheControl(noStoreCacheControl)
            .header("Accept-Encoding", "identity")
            .get()
            .build()

        val timingListener = HttpRttEventListener()
        val probeClient = client.newBuilder()
            .eventListener(timingListener)
            .readTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
            .build()

        val call = probeClient.newCall(request)
        val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause != null) {
                call.cancel()
            }
        }

        try {
            val fallbackStartNanos = System.nanoTime()
            val elapsedMs = call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw ServerHttpException(response.code)
                }
                // Drain 0-byte body so OkHttp marks the connection reusable immediately
                response.body?.bytes()
                val fallbackEndNanos = System.nanoTime()

                val rttNanos = if (
                    timingListener.requestStartNanos > 0L &&
                    timingListener.responseEndNanos > timingListener.requestStartNanos
                ) {
                    timingListener.responseEndNanos - timingListener.requestStartNanos
                } else {
                    fallbackEndNanos - fallbackStartNanos
                }
                rttNanos / 1_000_000.0
            }
            elapsedMs
        } catch (e: IOException) {
            if (!currentCoroutineContext().isActive) {
                throw CancellationException("Latency probe cancelled", e)
            }
            throw e
        } finally {
            cancelHandle?.dispose()
        }
    }

    /**
     * Sends one throwaway warm-up request (never counted) to establish TCP + TLS on a fresh
     * persistent connection, then measures [requestCount] sequential idle probes in Double ms.
     * - Timed-out samples are discarded instead of recording a timeout duration.
     * - Partial live jitter is never emitted while the ping phase is still running.
     * - Final ping (median) and final jitter (average absolute consecutive difference) are both
     *   calculated strictly from the same counted idle samples list.
     */
    suspend fun measureUnloadedLatency(
        requestCount: Int = SpeedTestConstants.PING_REQUEST_COUNT,
        onSample: suspend (
            completed: Int,
            total: Int,
            latestMs: Double,
            medianMs: Double,
        ) -> Unit = { _, _, _, _ -> },
    ): LatencyMeasurement = withContext(Dispatchers.IO) {
        if (singleProbeOverride == null) {
            // Clear any stale idle socket from a previous test so the single throwaway warm-up
            // request performs the TCP + TLS handshake cleanly before counted samples start.
            client.connectionPool.evictAll()
        }

        val warmupTimeoutMs = SpeedTestConstants.PING_CALL_TIMEOUT_SECONDS * 1_000L
        val probeTimeoutMs = SpeedTestConstants.PING_PROBE_TIMEOUT_MS
        val probeTimeoutCeilingMs = probeTimeoutMs.toDouble()

        // One throwaway warm-up request (connection setup + TLS handshake): never counted.
        executeProbeRttMs(callTimeoutMs = warmupTimeoutMs)
        if (probeSpacingMs > 0L) {
            delay(probeSpacingMs)
        }

        val idleSamplesMs = ArrayList<Double>(requestCount)
        var lastTimeoutException: IOException? = null

        for (index in 1..requestCount) {
            if (!currentCoroutineContext().isActive) break
            val rttMs = try {
                executeProbeRttMs(callTimeoutMs = probeTimeoutMs)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: IOException) {
                if (e.isTimeoutException()) {
                    // Discard timed-out sample instead of using the timeout duration
                    lastTimeoutException = e
                    if (index < requestCount && probeSpacingMs > 0L) {
                        delay(probeSpacingMs)
                    }
                    continue
                }
                throw e
            }

            if (rttMs.isFinite() && rttMs > 0.0 && rttMs < probeTimeoutCeilingMs) {
                idleSamplesMs.add(rttMs)
                val currentMedian = SpeedMath.calculateMedian(idleSamplesMs) ?: rttMs
                onSample(index, requestCount, rttMs, currentMedian)
            }

            if (index < requestCount && probeSpacingMs > 0L) {
                delay(probeSpacingMs)
            }
        }

        if (idleSamplesMs.isEmpty() && lastTimeoutException != null) {
            throw lastTimeoutException
        }

        val countedIdleSamples = idleSamplesMs.toList()
        val finalPingMs = SpeedMath.calculateMedian(countedIdleSamples)
        val finalJitterMs = SpeedMath.calculateJitter(countedIdleSamples)

        LatencyMeasurement(
            pingMs = finalPingMs,
            jitterMs = finalJitterMs,
            samplesMs = countedIdleSamples,
        )
    }

    /**
     * Continuous latency probe stream used concurrently during download and upload phases
     * to measure loaded latency (bufferbloat) on a separate connection.
     * Loaded latency samples are never used in unloaded jitter calculation.
     */
    fun probeFlow(intervalMs: Long = SpeedTestConstants.LOADED_LATENCY_INTERVAL_MS): Flow<Double> = flow {
        val loadedTimeoutMs = SpeedTestConstants.LOADED_PING_CALL_TIMEOUT_SECONDS * 1_000L
        // Warm up the dedicated loaded-latency connection (throwaway, not emitted)
        try {
            executeProbeRttMs(callTimeoutMs = loadedTimeoutMs)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: IOException) {
            // Continue attempting periodic probes
        }

        val timeoutCeilingMs = loadedTimeoutMs.toDouble()
        while (currentCoroutineContext().isActive) {
            val tickStartMs = System.nanoTime() / 1_000_000L
            try {
                val rttMs = executeProbeRttMs(callTimeoutMs = loadedTimeoutMs)
                if (rttMs.isFinite() && rttMs > 0.0 && rttMs < timeoutCeilingMs) {
                    emit(rttMs)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: IOException) {
                // Skip failed individual probe under congestion; never emit 0 or timeout values
            }
            val elapsedMs = (System.nanoTime() / 1_000_000L) - tickStartMs
            val waitMs = (intervalMs - elapsedMs).coerceAtLeast(50L)
            delay(waitMs)
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun executeProbeRttMs(callTimeoutMs: Long): Double =
        singleProbeOverride?.invoke() ?: measureSingleRttMs(callTimeoutMs = callTimeoutMs)

    private fun IOException.isTimeoutException(): Boolean {
        if (this is SocketTimeoutException || this is InterruptedIOException) {
            return true
        }
        return message?.contains("timeout", ignoreCase = true) == true
    }
}
