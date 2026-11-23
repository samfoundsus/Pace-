package com.example.data.constants

/**
 * Provisional thresholds for Bufferbloat grading and Estimated use-case ratings.
 * Kept in one constants file so thresholds can be calibrated easily in future phases.
 */
object RatingThresholds {
    // Bufferbloat: maximum latency increase (ms) above idle ping
    const val BUFFERBLOAT_GRADE_A_MAX_INCREASE_MS = 30.0
    const val BUFFERBLOAT_GRADE_B_MAX_INCREASE_MS = 60.0
    const val BUFFERBLOAT_GRADE_C_MAX_INCREASE_MS = 120.0
    const val BUFFERBLOAT_GRADE_D_MAX_INCREASE_MS = 200.0

    // Browsing: Good = download >= 5 Mbps AND ping < 100 ms
    const val BROWSING_GOOD_DOWNLOAD_MBPS = 5.0
    const val BROWSING_GOOD_PING_MS = 100.0
    const val BROWSING_OKAY_DOWNLOAD_MBPS = 2.0
    const val BROWSING_OKAY_PING_MS = 200.0

    // Streaming: HD >= 5 Mbps (Okay), 4K >= 25 Mbps (Good)
    const val STREAMING_HD_DOWNLOAD_MBPS = 5.0
    const val STREAMING_4K_DOWNLOAD_MBPS = 25.0

    // Video calls: Good = upload >= 3 Mbps, ping < 100 ms, jitter < 30 ms
    const val VIDEO_CALL_GOOD_UPLOAD_MBPS = 3.0
    const val VIDEO_CALL_GOOD_PING_MS = 100.0
    const val VIDEO_CALL_GOOD_JITTER_MS = 30.0
    const val VIDEO_CALL_OKAY_UPLOAD_MBPS = 1.5
    const val VIDEO_CALL_OKAY_PING_MS = 180.0
    const val VIDEO_CALL_OKAY_JITTER_MS = 60.0

    // Gaming: Good = ping < 50 ms, jitter < 20 ms, bufferbloat grade A or B
    const val GAMING_GOOD_PING_MS = 50.0
    const val GAMING_GOOD_JITTER_MS = 20.0
    const val GAMING_OKAY_PING_MS = 90.0
    const val GAMING_OKAY_JITTER_MS = 35.0
}
