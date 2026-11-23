package com.example.engine.speedtest

import com.example.data.models.ConnectionType
import com.example.data.models.TestResult
import com.example.data.models.TestStage
import com.example.engine.latency.LatencyEngine
import com.example.ui.components.formatJitterReadout
import com.example.viewmodel.SpeedTestUiState
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.SocketTimeoutException

class SpeedMathTest {

    @Test
    fun calculateMbps_matchesFormula() {
        // 12,500,000 bytes in 1 second = 100 Mbps
        val mbps = SpeedMath.calculateMbps(bytes = 12_500_000L, durationNanos = 1_000_000_000L)
        assertEquals(100.0, mbps!!, 0.0001)
    }

    @Test
    fun calculateMbps_returnsNullWhenUnavailable() {
        assertNull(SpeedMath.calculateMbps(bytes = 0L, durationNanos = 1_000_000_000L))
        assertNull(SpeedMath.calculateMbps(bytes = 1000L, durationNanos = 0L))
        assertNull(SpeedMath.calculateMbps(bytes = -10L, durationNanos = 1_000_000_000L))
    }

    @Test
    fun calculateMedian_handlesOddAndEvenCounts() {
        assertNull(SpeedMath.calculateMedian(emptyList()))
        assertEquals(20.0, SpeedMath.calculateMedian(listOf(30.0, 10.0, 20.0))!!, 0.0001)
        assertEquals(17.5, SpeedMath.calculateMedian(listOf(10.0, 25.0, 15.0, 20.0))!!, 0.0001)
    }

    @Test
    fun calculateJitter_requiresAtLeastThreeSamplesAndAveragesConsecutiveDiffs() {
        assertNull(SpeedMath.calculateJitter(emptyList()))
        assertNull(SpeedMath.calculateJitter(listOf(15.0)))
        assertNull(SpeedMath.calculateJitter(listOf(15.0, 18.0)))
        assertNull(SpeedMath.calculateJitter(listOf(15.0, Double.NaN, 18.0)))
        assertNull(SpeedMath.calculateJitter(listOf(15.0, -2.0, 18.0)))
        // |20-10|=10, |14-20|=6, |18-14|=4 -> avg = 20 / 3 = 6.6667
        val jitter = SpeedMath.calculateJitter(listOf(10.0, 20.0, 14.0, 18.0))
        assertEquals(20.0 / 3.0, jitter!!, 0.0001)
    }

    @Test
    fun guardJitter_returnsNullForNullNaNNegativeOrGreaterThanMaxIdlePing() {
        assertNull(SpeedMath.guardJitter(jitterMs = null, maxIdlePingMs = 66.2))
        assertNull(SpeedMath.guardJitter(jitterMs = Double.NaN, maxIdlePingMs = 66.2))
        assertNull(SpeedMath.guardJitter(jitterMs = -1.0, maxIdlePingMs = 66.2))
        assertNull(SpeedMath.guardJitter(jitterMs = 1306.0, maxIdlePingMs = 66.2))
        assertNull(SpeedMath.guardJitter(jitterMs = 784.0, maxIdlePingMs = 34.0))
        assertEquals(2.4, SpeedMath.guardJitter(jitterMs = 2.4, maxIdlePingMs = 66.2)!!, 0.0001)
    }

    @Test
    fun formatJitterReadout_alwaysUsesOneDecimalPlaceOrDash() {
        assertEquals("-", formatJitterReadout(null))
        assertEquals("-", formatJitterReadout(Double.NaN))
        assertEquals("-", formatJitterReadout(-3.0))
        assertEquals("2.4", formatJitterReadout(2.44))
        assertEquals("125.7", formatJitterReadout(125.68))
    }

    @Test
    fun latencyEngine_excludesWarmupAndDiscardsTimedOutOrOverCeilingSamples() = runBlocking {
        // First value (850.0) is the throwaway warm-up (connection setup / TLS handshake)
        val probeResults = ArrayDeque<Any>(
            listOf(
                850.0,
                31.0,
                33.0,
                SocketTimeoutException("timeout"),
                5940.0, // exceeds warm probe timeout ceiling (2000 ms) -> discarded
                32.0,
                34.0,
            ),
        )
        val engine = LatencyEngine(
            client = OkHttpClient(),
            singleProbeOverride = {
                when (val next = probeResults.removeFirst()) {
                    is Double -> next
                    is Throwable -> throw next
                    else -> error("Unexpected probe stub")
                }
            },
            probeSpacingMs = 0L,
        )

        val measurement = engine.measureUnloadedLatency(requestCount = 6)
        // Warm-up (850 ms), timeout, and 5940 ms stall are excluded -> only 4 counted idle samples
        assertEquals(listOf(31.0, 33.0, 32.0, 34.0), measurement.samplesMs)
        assertEquals(32.5, measurement.pingMs!!, 0.0001)
        // |33-31|=2, |32-33|=1, |34-32|=2 -> 5 / 3 = 1.6667 ms
        assertEquals(5.0 / 3.0, measurement.jitterMs!!, 0.0001)
    }

    @Test
    fun latencyEngine_returnsNullJitterWhenFewerThanThreeValidSamples() = runBlocking {
        val probeResults = ArrayDeque<Any>(
            listOf(
                900.0, // warm-up
                32.0,
                SocketTimeoutException("timeout"),
                34.0,
            ),
        )
        val engine = LatencyEngine(
            client = OkHttpClient(),
            singleProbeOverride = {
                when (val next = probeResults.removeFirst()) {
                    is Double -> next
                    is Throwable -> throw next
                    else -> error("Unexpected probe stub")
                }
            },
            probeSpacingMs = 0L,
        )

        val measurement = engine.measureUnloadedLatency(requestCount = 3)
        assertEquals(listOf(32.0, 34.0), measurement.samplesMs)
        assertEquals(33.0, measurement.pingMs!!, 0.0001)
        assertNull(measurement.jitterMs)
    }

    @Test
    fun uiState_hidesJitterWhilePingPhaseIsRunning() {
        val duringPing = SpeedTestUiState(
            stage = TestStage.PING,
            livePingMs = 32.0,
            liveJitterMs = 2.5,
        )
        assertNull(duringPing.displayedJitterMs)

        val afterPing = duringPing.copy(stage = TestStage.DOWNLOAD)
        assertEquals(2.5, afterPing.displayedJitterMs!!, 0.0001)
    }

    @Test
    fun rollingSpeedTracker_smoothsSustainedThroughput() {
        val tracker = RollingSpeedTracker(windowNanos = 1_000_000_000L, emaAlpha = 0.35)
        val start = 1_000_000_000L
        tracker.reset(start)

        // Feed 1.25 MB every 100ms (= 100 Mbps steady)
        var lastMbps = 0.0
        for (tick in 1..15) {
            val now = start + tick * 100_000_000L
            val bytes = tick * 1_250_000L
            lastMbps = tracker.recordAndGetSmoothedMbps(now, bytes)
        }
        assertEquals(100.0, lastMbps, 0.5)
    }

    @Test
    fun testResult_allowsNullUnavailableFieldsAndLoadedLatencyPlaceholders() {
        val result = TestResult(
            timestamp = 1700000000000L,
            connectionType = ConnectionType.WIFI,
            ping = 12.4,
            jitter = 1.8,
            download = 185.2,
            upload = null,
        )
        assertNull(result.upload)
        assertNull(result.downloadLoadedLatency)
        assertNull(result.uploadLoadedLatency)
    }
}
