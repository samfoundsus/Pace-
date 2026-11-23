package com.example.data.models

import com.example.data.settings.SpeedUnit
import com.example.engine.speedtest.SpeedMath
import com.example.ui.components.convertSpeedFromMbps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultEvaluationTest {

    @Test
    fun bufferbloatGrade_matchesThresholdsForLargestIncrease() {
        // Under 30 ms -> A
        assertEquals(
            BufferbloatGrade.A,
            ResultEvaluator.computeBufferbloatGrade(idlePingMs = 20.0, downloadLoadedMs = 45.0, uploadLoadedMs = 35.0),
        )
        // Under 60 ms -> B (upload increase is 45 ms)
        assertEquals(
            BufferbloatGrade.B,
            ResultEvaluator.computeBufferbloatGrade(idlePingMs = 20.0, downloadLoadedMs = 35.0, uploadLoadedMs = 65.0),
        )
        // Under 120 ms -> C (download increase is 90 ms)
        assertEquals(
            BufferbloatGrade.C,
            ResultEvaluator.computeBufferbloatGrade(idlePingMs = 20.0, downloadLoadedMs = 110.0, uploadLoadedMs = 40.0),
        )
        // Under 200 ms -> D (upload increase is 150 ms)
        assertEquals(
            BufferbloatGrade.D,
            ResultEvaluator.computeBufferbloatGrade(idlePingMs = 20.0, downloadLoadedMs = 50.0, uploadLoadedMs = 170.0),
        )
        // 200 ms or higher -> F
        assertEquals(
            BufferbloatGrade.F,
            ResultEvaluator.computeBufferbloatGrade(idlePingMs = 20.0, downloadLoadedMs = 225.0, uploadLoadedMs = 50.0),
        )
    }

    @Test
    fun bufferbloatGrade_returnsNullWhenUnavailable() {
        assertNull(ResultEvaluator.computeBufferbloatGrade(idlePingMs = null, downloadLoadedMs = 40.0, uploadLoadedMs = 40.0))
        assertNull(ResultEvaluator.computeBufferbloatGrade(idlePingMs = 20.0, downloadLoadedMs = null, uploadLoadedMs = null))
        // Works when only one of download/upload loaded latency is available
        assertEquals(
            BufferbloatGrade.A,
            ResultEvaluator.computeBufferbloatGrade(idlePingMs = 20.0, downloadLoadedMs = 35.0, uploadLoadedMs = null),
        )
    }

    @Test
    fun evaluateUseCases_gradesAllFourUseCasesAccurately() {
        val fastResult = TestResult(
            timestamp = 1_700_000_000_000L,
            connectionType = ConnectionType.WIFI,
            ping = 18.0,
            jitter = 3.2,
            download = 120.0,
            upload = 40.0,
            downloadLoadedLatency = 32.0,
            uploadLoadedLatency = 42.0,
            bufferbloatGrade = BufferbloatGrade.A,
        )
        val fastRatings = ResultEvaluator.evaluateUseCases(fastResult).associateBy { it.useCase }
        assertEquals(RatingLevel.GOOD, fastRatings[UseCaseType.BROWSING]?.level)
        assertEquals(RatingLevel.GOOD, fastRatings[UseCaseType.STREAMING]?.level)
        assertEquals(RatingLevel.GOOD, fastRatings[UseCaseType.VIDEO_CALLS]?.level)
        assertEquals(RatingLevel.GOOD, fastRatings[UseCaseType.GAMING]?.level)

        val hdOnlyResult = fastResult.copy(
            download = 12.0,
            upload = 2.0,
            ping = 35.0,
            jitter = 10.0,
            bufferbloatGrade = BufferbloatGrade.D,
        )
        val mixedRatings = ResultEvaluator.evaluateUseCases(hdOnlyResult).associateBy { it.useCase }
        assertEquals(RatingLevel.GOOD, mixedRatings[UseCaseType.BROWSING]?.level)
        assertEquals(RatingLevel.OKAY, mixedRatings[UseCaseType.STREAMING]?.level)
        assertEquals(RatingLevel.OKAY, mixedRatings[UseCaseType.VIDEO_CALLS]?.level)
        assertEquals(RatingLevel.POOR, mixedRatings[UseCaseType.GAMING]?.level)
    }

    @Test
    fun jitter_neverExceedsPingRange() {
        val samples = listOf(36.2, 39.4, 37.8, 40.1, 38.0, 37.5, 41.2, 38.9, 36.7, 38.4)
        val range = samples.max() - samples.min()
        val jitter = SpeedMath.calculateJitter(samples)!!
        assertTrue("Expected jitter ($jitter) <= sample range ($range)", jitter <= range)
    }

    @Test
    fun speedUnitConversion_convertsMbpsToMBPerSec() {
        assertEquals(80.0, convertSpeedFromMbps(80.0, SpeedUnit.MBPS), 0.0001)
        assertEquals(10.0, convertSpeedFromMbps(80.0, SpeedUnit.MB_PER_SEC), 0.0001)
    }

    @Test
    fun sanitizeSsid_neverAllowsUnknownSsidOrQuotes() {
        assertNull(com.example.engine.speedtest.NetworkMonitor.sanitizeSsid(null))
        assertNull(com.example.engine.speedtest.NetworkMonitor.sanitizeSsid(""))
        assertNull(com.example.engine.speedtest.NetworkMonitor.sanitizeSsid("<unknown ssid>"))
        assertNull(com.example.engine.speedtest.NetworkMonitor.sanitizeSsid("\"<unknown ssid>\""))
        assertNull(com.example.engine.speedtest.NetworkMonitor.sanitizeSsid("0x"))
        assertEquals(
            "HomeFiber_5G",
            com.example.engine.speedtest.NetworkMonitor.sanitizeSsid("\"HomeFiber_5G\""),
        )
    }

    @Test
    fun latencyReadout_showsDashWhenNullNeverZero() {
        assertEquals("-", com.example.ui.components.formatLatencyReadout(null))
        assertEquals("24.5", com.example.ui.components.formatLatencyReadout(24.5))
    }
}
