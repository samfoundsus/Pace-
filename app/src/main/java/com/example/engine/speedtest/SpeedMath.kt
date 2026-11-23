package com.example.engine.speedtest

import com.example.data.constants.SpeedTestConstants
import kotlin.math.abs

/**
 * Pure mathematical utilities for speed test calculations using monotonic nanosecond timestamps.
 */
object SpeedMath {
    private const val NANOS_PER_SECOND = 1_000_000_000.0
    private const val BITS_PER_BYTE = 8.0
    private const val BITS_PER_MEGABIT = 1_000_000.0

    /**
     * Formula: Mbps = (bytes * 8) / (seconds * 1,000,000).
     * Returns null if bytes or duration are non-positive.
     */
    fun calculateMbps(bytes: Long, durationNanos: Long): Double? {
        if (bytes <= 0L || durationNanos <= 0L) return null
        val seconds = durationNanos / NANOS_PER_SECOND
        if (seconds <= 0.0) return null
        return (bytes * BITS_PER_BYTE) / (seconds * BITS_PER_MEGABIT)
    }

    /**
     * Median of latency samples in milliseconds, or null when empty.
     */
    fun calculateMedian(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        }
    }

    /**
     * Single authoritative function that produces the final jitter value in milliseconds
     * from the counted idle ping samples (the same samples used for the ping median).
     *
     * Formula: average absolute difference between consecutive idle ping samples.
     *
     * Guard: returns null (displayed as "-") if fewer than [SpeedTestConstants.MIN_JITTER_SAMPLES]
     * (3) valid idle ping samples exist, or if the computed jitter is null, NaN, infinite,
     * negative, or greater than the largest idle ping sample.
     */
    fun calculateJitter(idlePingSamplesMs: List<Double>): Double? {
        if (idlePingSamplesMs.size < SpeedTestConstants.MIN_JITTER_SAMPLES) return null
        if (idlePingSamplesMs.any { !it.isFinite() || it <= 0.0 }) return null

        val maxIdlePingMs = idlePingSamplesMs.maxOrNull() ?: return null
        var sumAbsDiffsMs = 0.0
        for (i in 1 until idlePingSamplesMs.size) {
            sumAbsDiffsMs += abs(idlePingSamplesMs[i] - idlePingSamplesMs[i - 1])
        }
        val jitterMs: Double? = sumAbsDiffsMs / (idlePingSamplesMs.size - 1).toDouble()

        return guardJitter(jitterMs = jitterMs, maxIdlePingMs = maxIdlePingMs)
    }

    internal fun guardJitter(jitterMs: Double?, maxIdlePingMs: Double): Double? {
        if (
            jitterMs == null ||
            jitterMs.isNaN() ||
            !jitterMs.isFinite() ||
            jitterMs < 0.0 ||
            !maxIdlePingMs.isFinite() ||
            maxIdlePingMs <= 0.0 ||
            jitterMs > maxIdlePingMs
        ) {
            return null
        }
        return jitterMs
    }
}

/**
 * Computes a smoothed rolling-window throughput average (Mbps) for ~10 Hz gauge updates.
 */
class RollingSpeedTracker(
    private val windowNanos: Long,
    private val emaAlpha: Double,
) {
    private data class Snapshot(val timestampNanos: Long, val cumulativeBytes: Long)

    private val history = ArrayDeque<Snapshot>()
    private var smoothedMbps: Double? = null

    fun reset(startNanos: Long) {
        history.clear()
        history.addLast(Snapshot(startNanos, 0L))
        smoothedMbps = null
    }

    fun recordAndGetSmoothedMbps(nowNanos: Long, cumulativeBytes: Long): Double {
        if (history.isEmpty()) {
            history.addLast(Snapshot(nowNanos, cumulativeBytes))
            return 0.0
        }

        history.addLast(Snapshot(nowNanos, cumulativeBytes))

        val cutoff = nowNanos - windowNanos
        while (history.size > 2 && history[1].timestampNanos <= cutoff) {
            history.removeFirst()
        }

        val oldest = history.first()
        val deltaBytes = (cumulativeBytes - oldest.cumulativeBytes).coerceAtLeast(0L)
        val deltaNanos = (nowNanos - oldest.timestampNanos).coerceAtLeast(1L)

        val windowMbps = SpeedMath.calculateMbps(deltaBytes, deltaNanos) ?: 0.0
        val next = when (val previous = smoothedMbps) {
            null -> windowMbps
            else -> (emaAlpha * windowMbps) + ((1.0 - emaAlpha) * previous)
        }.coerceAtLeast(0.0)

        smoothedMbps = next
        return next
    }
}
