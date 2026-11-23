package com.example.data.models

import com.example.data.constants.RatingThresholds

enum class BufferbloatGrade {
    A,
    B,
    C,
    D,
    F,
}

enum class RatingLevel {
    GOOD,
    OKAY,
    POOR,
}

enum class UseCaseType {
    BROWSING,
    STREAMING,
    VIDEO_CALLS,
    GAMING,
}

enum class RatingReason {
    BROWSING_GOOD,
    BROWSING_OKAY,
    BROWSING_POOR,
    STREAMING_GOOD,
    STREAMING_OKAY,
    STREAMING_POOR,
    VIDEO_CALLS_GOOD,
    VIDEO_CALLS_OKAY,
    VIDEO_CALLS_POOR,
    GAMING_GOOD,
    GAMING_OKAY,
    GAMING_POOR,
    UNAVAILABLE,
}

data class UseCaseRating(
    val useCase: UseCaseType,
    val level: RatingLevel,
    val reason: RatingReason,
)

/**
 * Pure domain evaluator for Bufferbloat grading and estimated use-case ratings.
 */
object ResultEvaluator {

    /**
     * Grades bufferbloat based on the largest latency increase over idle ping across download and upload.
     * Returns null when idle ping or both loaded latencies are unavailable.
     */
    fun computeBufferbloatGrade(
        idlePingMs: Double?,
        downloadLoadedMs: Double?,
        uploadLoadedMs: Double?,
    ): BufferbloatGrade? {
        if (idlePingMs == null) return null
        val dlIncrease = downloadLoadedMs?.let { (it - idlePingMs).coerceAtLeast(0.0) }
        val ulIncrease = uploadLoadedMs?.let { (it - idlePingMs).coerceAtLeast(0.0) }
        val maxIncrease = listOfNotNull(dlIncrease, ulIncrease).maxOrNull() ?: return null

        return when {
            maxIncrease < RatingThresholds.BUFFERBLOAT_GRADE_A_MAX_INCREASE_MS -> BufferbloatGrade.A
            maxIncrease < RatingThresholds.BUFFERBLOAT_GRADE_B_MAX_INCREASE_MS -> BufferbloatGrade.B
            maxIncrease < RatingThresholds.BUFFERBLOAT_GRADE_C_MAX_INCREASE_MS -> BufferbloatGrade.C
            maxIncrease < RatingThresholds.BUFFERBLOAT_GRADE_D_MAX_INCREASE_MS -> BufferbloatGrade.D
            else -> BufferbloatGrade.F
        }
    }

    fun evaluateUseCases(result: TestResult): List<UseCaseRating> {
        val grade = result.bufferbloatGrade ?: computeBufferbloatGrade(
            idlePingMs = result.ping,
            downloadLoadedMs = result.downloadLoadedLatency,
            uploadLoadedMs = result.uploadLoadedLatency,
        )
        return listOf(
            evaluateBrowsing(result.download, result.ping),
            evaluateStreaming(result.download),
            evaluateVideoCalls(result.upload, result.ping, result.jitter),
            evaluateGaming(result.ping, result.jitter, grade),
        )
    }

    private fun evaluateBrowsing(downloadMbps: Double?, pingMs: Double?): UseCaseRating {
        if (downloadMbps == null || pingMs == null) {
            return UseCaseRating(UseCaseType.BROWSING, RatingLevel.POOR, RatingReason.UNAVAILABLE)
        }
        return when {
            downloadMbps >= RatingThresholds.BROWSING_GOOD_DOWNLOAD_MBPS &&
                pingMs < RatingThresholds.BROWSING_GOOD_PING_MS ->
                UseCaseRating(UseCaseType.BROWSING, RatingLevel.GOOD, RatingReason.BROWSING_GOOD)

            downloadMbps >= RatingThresholds.BROWSING_OKAY_DOWNLOAD_MBPS &&
                pingMs < RatingThresholds.BROWSING_OKAY_PING_MS ->
                UseCaseRating(UseCaseType.BROWSING, RatingLevel.OKAY, RatingReason.BROWSING_OKAY)

            else ->
                UseCaseRating(UseCaseType.BROWSING, RatingLevel.POOR, RatingReason.BROWSING_POOR)
        }
    }

    private fun evaluateStreaming(downloadMbps: Double?): UseCaseRating {
        if (downloadMbps == null) {
            return UseCaseRating(UseCaseType.STREAMING, RatingLevel.POOR, RatingReason.UNAVAILABLE)
        }
        return when {
            downloadMbps >= RatingThresholds.STREAMING_4K_DOWNLOAD_MBPS ->
                UseCaseRating(UseCaseType.STREAMING, RatingLevel.GOOD, RatingReason.STREAMING_GOOD)

            downloadMbps >= RatingThresholds.STREAMING_HD_DOWNLOAD_MBPS ->
                UseCaseRating(UseCaseType.STREAMING, RatingLevel.OKAY, RatingReason.STREAMING_OKAY)

            else ->
                UseCaseRating(UseCaseType.STREAMING, RatingLevel.POOR, RatingReason.STREAMING_POOR)
        }
    }

    private fun evaluateVideoCalls(uploadMbps: Double?, pingMs: Double?, jitterMs: Double?): UseCaseRating {
        if (uploadMbps == null || pingMs == null || jitterMs == null) {
            return UseCaseRating(UseCaseType.VIDEO_CALLS, RatingLevel.POOR, RatingReason.UNAVAILABLE)
        }
        return when {
            uploadMbps >= RatingThresholds.VIDEO_CALL_GOOD_UPLOAD_MBPS &&
                pingMs < RatingThresholds.VIDEO_CALL_GOOD_PING_MS &&
                jitterMs < RatingThresholds.VIDEO_CALL_GOOD_JITTER_MS ->
                UseCaseRating(UseCaseType.VIDEO_CALLS, RatingLevel.GOOD, RatingReason.VIDEO_CALLS_GOOD)

            uploadMbps >= RatingThresholds.VIDEO_CALL_OKAY_UPLOAD_MBPS &&
                pingMs < RatingThresholds.VIDEO_CALL_OKAY_PING_MS &&
                jitterMs < RatingThresholds.VIDEO_CALL_OKAY_JITTER_MS ->
                UseCaseRating(UseCaseType.VIDEO_CALLS, RatingLevel.OKAY, RatingReason.VIDEO_CALLS_OKAY)

            else ->
                UseCaseRating(UseCaseType.VIDEO_CALLS, RatingLevel.POOR, RatingReason.VIDEO_CALLS_POOR)
        }
    }

    private fun evaluateGaming(
        pingMs: Double?,
        jitterMs: Double?,
        bufferbloatGrade: BufferbloatGrade?,
    ): UseCaseRating {
        if (pingMs == null || jitterMs == null) {
            return UseCaseRating(UseCaseType.GAMING, RatingLevel.POOR, RatingReason.UNAVAILABLE)
        }
        val goodGrade = bufferbloatGrade == BufferbloatGrade.A || bufferbloatGrade == BufferbloatGrade.B
        val okayGrade = bufferbloatGrade == null || goodGrade || bufferbloatGrade == BufferbloatGrade.C
        return when {
            pingMs < RatingThresholds.GAMING_GOOD_PING_MS &&
                jitterMs < RatingThresholds.GAMING_GOOD_JITTER_MS &&
                goodGrade ->
                UseCaseRating(UseCaseType.GAMING, RatingLevel.GOOD, RatingReason.GAMING_GOOD)

            pingMs < RatingThresholds.GAMING_OKAY_PING_MS &&
                jitterMs < RatingThresholds.GAMING_OKAY_JITTER_MS &&
                okayGrade ->
                UseCaseRating(UseCaseType.GAMING, RatingLevel.OKAY, RatingReason.GAMING_OKAY)

            else ->
                UseCaseRating(UseCaseType.GAMING, RatingLevel.POOR, RatingReason.GAMING_POOR)
        }
    }
}
