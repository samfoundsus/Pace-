package com.example.engine.tools

import com.example.data.models.SpeedSample
import com.example.engine.export.ResultPdfExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase4FinalTest {

    @Test
    fun estimateDataUsageMb_usesMbpsTimesSecondsDividedByEight() {
        // 80 Mbps download + 40 Mbps upload for 30s = (80*30/8) + (40*30/8) = 300 + 150 = 450 MB
        val estimated = ExtendedTestStats.estimateDataUsageMb(
            lastDownloadMbps = 80.0,
            lastUploadMbps = 40.0,
            phaseSeconds = 30,
        )
        assertNotNull(estimated)
        assertEquals(450.0, estimated!!, 0.001)

        // 60s should double to 900 MB
        val estimated60 = ExtendedTestStats.estimateDataUsageMb(
            lastDownloadMbps = 80.0,
            lastUploadMbps = 40.0,
            phaseSeconds = 60,
        )
        assertEquals(900.0, estimated60!!, 0.001)

        // Missing prior data returns null
        assertNull(
            ExtendedTestStats.estimateDataUsageMb(
                lastDownloadMbps = null,
                lastUploadMbps = null,
                phaseSeconds = 30,
            ),
        )
    }

    @Test
    fun computePhaseSummary_calculatesAverageMinMaxAndStability() {
        val steadySamples = listOf(
            SpeedSample(2.0f, 95.0),
            SpeedSample(3.0f, 100.0),
            SpeedSample(4.0f, 105.0),
            SpeedSample(5.0f, 98.0),
            SpeedSample(6.0f, 102.0),
        )
        val steadySummary = ExtendedTestStats.computePhaseSummary(steadySamples)
        assertNotNull(steadySummary)
        assertEquals(100.0, steadySummary!!.averageMbps, 0.01)
        assertEquals(95.0, steadySummary.minMbps, 0.01)
        assertEquals(105.0, steadySummary.maxMbps, 0.01)
        assertEquals(100, steadySummary.stabilityPercent)

        // 2 samples inside [80..120], 2 outside -> 50% stability
        val spikySamples = listOf(
            SpeedSample(2.0f, 50.0),
            SpeedSample(3.0f, 100.0),
            SpeedSample(4.0f, 100.0),
            SpeedSample(5.0f, 150.0),
        )
        val spikySummary = ExtendedTestStats.computePhaseSummary(spikySamples)
        assertNotNull(spikySummary)
        assertEquals(100.0, spikySummary!!.averageMbps, 0.01)
        assertEquals(50.0, spikySummary.minMbps, 0.01)
        assertEquals(150.0, spikySummary.maxMbps, 0.01)
        assertEquals(50, spikySummary.stabilityPercent)
    }

    @Test
    fun computePhaseSummaryExcludingWarmup_ignoresFirst1Point5Seconds() {
        val samples = listOf(
            SpeedSample(0.2f, 5.0),
            SpeedSample(0.8f, 15.0),
            SpeedSample(1.4f, 40.0),
            SpeedSample(1.5f, 100.0),
            SpeedSample(5.0f, 110.0),
            SpeedSample(10.0f, 90.0),
        )
        val summary = ExtendedTestStats.computePhaseSummaryExcludingWarmup(samples)
        assertNotNull(summary)
        assertEquals(100.0, summary!!.averageMbps, 0.01)
        assertEquals(90.0, summary.minMbps, 0.01)
        assertEquals(110.0, summary.maxMbps, 0.01)
        assertEquals(100, summary.stabilityPercent)
        assertEquals(3, summary.sampleCount)
    }

    @Test
    fun downsampleSamples_returnsIndependentImmutableSnapshotAndCapsAt300() {
        val mutableInput = arrayListOf(
            SpeedSample(0.1f, 20.0),
            SpeedSample(0.2f, 35.0),
        )
        val snapshot = ExtendedTestStats.downsampleSamples(mutableInput, ExtendedTestStats.MAX_GRAPH_POINTS)
        assertNotSame(mutableInput, snapshot)
        mutableInput.add(SpeedSample(0.3f, 50.0))
        assertEquals(2, snapshot.size)

        val raw = List(650) { index ->
            SpeedSample(elapsedSeconds = index * 0.1f, speedMbps = 50.0 + (index % 10))
        }
        val downsampled = ExtendedTestStats.downsampleSamples(raw, ExtendedTestStats.MAX_GRAPH_POINTS)
        assertTrue(downsampled.size <= ExtendedTestStats.MAX_GRAPH_POINTS)
        assertEquals(raw.last().elapsedSeconds, downsampled.last().elapsedSeconds, 0.0001f)
        assertEquals(raw.last().speedMbps, downsampled.last().speedMbps, 0.0001)
    }

    @Test
    fun computeYAxisCeiling_adds15PercentHeadroomAndRoundsUpCleanly() {
        // 80 * 1.15 = 92 -> rounds to 100
        assertEquals(100.0, ExtendedTestStats.computeYAxisCeiling(80.0), 0.001)
        // 100 * 1.15 = 115 -> rounds to 125
        assertEquals(125.0, ExtendedTestStats.computeYAxisCeiling(100.0), 0.001)
        // 200 * 1.15 = 230 -> rounds to 250
        assertEquals(250.0, ExtendedTestStats.computeYAxisCeiling(200.0), 0.001)
        // 0 -> safe minimum 10
        assertEquals(10.0, ExtendedTestStats.computeYAxisCeiling(0.0), 0.001)
    }

    @Test
    fun suggestedPdfFileName_hasPacePrefixAndPdfExtension() {
        val fileName = ResultPdfExporter.suggestedFileName(1_760_000_000_000L)
        assertTrue(fileName.startsWith("Pace_SpeedTest_"))
        assertTrue(fileName.endsWith(".pdf"))
    }
}
