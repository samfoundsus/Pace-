package com.example.engine.tools

import androidx.compose.runtime.Immutable
import com.example.data.models.SpeedSample
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

enum class ExtendedDuration(val seconds: Int) {
    THIRTY_SECONDS(30),
    SIXTY_SECONDS(60),
}

@Immutable
data class PhaseSummaryStats(
    val averageMbps: Double,
    val minMbps: Double,
    val maxMbps: Double,
    val stabilityPercent: Int,
    val sampleCount: Int,
)

/**
 * Pure statistical, axis-scaling, and data-budget helpers for Extended Test and live throughput graphs.
 */
object ExtendedTestStats {

    const val MAX_GRAPH_POINTS = 300
    const val WARMUP_SECONDS = 1.5f

    /**
     * Estimates total data transferred in MB for a download + upload extended test using the
     * formula: Mbps * seconds / 8. Returns null if no valid prior speed is available.
     */
    fun estimateDataUsageMb(
        lastDownloadMbps: Double?,
        lastUploadMbps: Double?,
        phaseSeconds: Int,
    ): Double? {
        if (phaseSeconds <= 0) return null
        val dl = lastDownloadMbps?.takeIf { it.isFinite() && it > 0.0 }
        val ul = lastUploadMbps?.takeIf { it.isFinite() && it > 0.0 }
        if (dl == null && ul == null) return null

        val downloadMb = (dl ?: 0.0) * phaseSeconds / 8.0
        val uploadMb = (ul ?: 0.0) * phaseSeconds / 8.0
        return downloadMb + uploadMb
    }

    /**
     * Computes average, min, max, and stability percentage from post-warmup throughput samples.
     * Stability is the percentage of samples that stayed within 20% of the average
     * (i.e. within [0.8 * avg, 1.2 * avg], retaining at least 80% consistency around the average).
     */
    fun computePhaseSummary(
        postWarmupSamples: List<SpeedSample>,
        overallAverageMbps: Double? = null,
    ): PhaseSummaryStats? {
        val speeds = postWarmupSamples
            .map { it.speedMbps }
            .filter { it.isFinite() && it >= 0.0 }
        if (speeds.isEmpty()) return null

        val average = overallAverageMbps?.takeIf { it.isFinite() && it > 0.0 } ?: speeds.average()
        val min = speeds.min()
        val max = speeds.max()
        val stabilityPercent = if (average <= 0.0) {
            0
        } else {
            val tolerance = average * 0.20
            val withinBand = speeds.count { abs(it - average) <= tolerance }
            ((withinBand.toDouble() / speeds.size) * 100.0).roundToInt().coerceIn(0, 100)
        }

        return PhaseSummaryStats(
            averageMbps = average,
            minMbps = min,
            maxMbps = max,
            stabilityPercent = stabilityPercent,
            sampleCount = speeds.size,
        )
    }

    /**
     * Filters out samples collected during the initial [warmUpSeconds] warm-up period
     * (default 1.5s) and computes [PhaseSummaryStats] from the remaining steady-state samples.
     */
    fun computePhaseSummaryExcludingWarmup(
        allSamples: List<SpeedSample>,
        warmUpSeconds: Float = WARMUP_SECONDS,
    ): PhaseSummaryStats? {
        val postWarmup = allSamples.filter { it.elapsedSeconds >= warmUpSeconds }
        return computePhaseSummary(postWarmupSamples = postWarmup)
    }

    /**
     * Computes a clean round Y-axis ceiling strictly above the highest sample (and optional average)
     * plus 15% headroom so graphs never clip at the top edge.
     */
    fun computeYAxisCeiling(
        maxSampleMbps: Double,
        averageMbps: Double? = null,
    ): Double {
        val peak = maxOf(
            if (maxSampleMbps.isFinite()) maxSampleMbps else 0.0,
            averageMbps?.takeIf { it.isFinite() } ?: 0.0,
        )
        if (peak <= 0.0) return 10.0
        val padded = peak * 1.15
        return when {
            padded <= 10.0 -> ceil(padded / 2.0) * 2.0
            padded <= 25.0 -> ceil(padded / 5.0) * 5.0
            padded <= 100.0 -> ceil(padded / 10.0) * 10.0
            padded <= 250.0 -> ceil(padded / 25.0) * 25.0
            padded <= 500.0 -> ceil(padded / 50.0) * 50.0
            else -> ceil(padded / 100.0) * 100.0
        }
    }

    /**
     * Caps a time-series list at [maxPoints] by merging adjacent older points while keeping
     * the most recent portion at full resolution so live graphs stay smooth and bounded.
     *
     * Always returns a fresh immutable snapshot list so StateFlow and Compose never observe
     * an aliased mutable list reference.
     */
    fun downsampleSamples(
        samples: List<SpeedSample>,
        maxPoints: Int = MAX_GRAPH_POINTS,
    ): List<SpeedSample> {
        if (samples.isEmpty()) return emptyList()
        if (samples.size <= maxPoints || maxPoints < 4) return samples.toList()

        val recentKeepCount = (maxPoints / 2).coerceAtLeast(2)
        val olderBudget = (maxPoints - recentKeepCount).coerceAtLeast(2)
        val olderCount = samples.size - recentKeepCount
        if (olderCount <= olderBudget) return samples.toList()

        val result = ArrayList<SpeedSample>(maxPoints)
        val bucketSize = olderCount.toDouble() / olderBudget.toDouble()
        for (i in 0 until olderBudget) {
            val start = (i * bucketSize).toInt().coerceIn(0, olderCount - 1)
            val end = (((i + 1) * bucketSize).toInt()).coerceIn(start + 1, olderCount)
            var sumTime = 0.0
            var sumSpeed = 0.0
            val count = end - start
            for (idx in start until end) {
                val sample = samples[idx]
                sumTime += sample.elapsedSeconds
                sumSpeed += sample.speedMbps
            }
            result.add(
                SpeedSample(
                    elapsedSeconds = (sumTime / count).toFloat(),
                    speedMbps = sumSpeed / count,
                ),
            )
        }
        for (idx in olderCount until samples.size) {
            result.add(samples[idx])
        }
        return result
    }
}
