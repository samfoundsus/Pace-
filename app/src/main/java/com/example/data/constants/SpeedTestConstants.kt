package com.example.data.constants

/**
 * Centralized configuration for the speed test engine and server selection.
 * Server endpoint URLs live strictly in [com.example.data.server.ServerProfile].
 */
object SpeedTestConstants {
    // Public community server directory (fetched only on user action, cached 24 hours)
    const val COMMUNITY_SERVERS_URL = "https://librespeed.org/backend-servers/servers.php"
    const val COMMUNITY_CACHE_TTL_MS = 24L * 60L * 60L * 1_000L

    // Server health probing & automatic fallback
    const val SERVER_HEALTH_CONCURRENCY = 8
    const val SERVER_HEALTH_PROBES = 3
    const val SERVER_HEALTH_TIMEOUT_MS = 2_000L
    const val NO_DATA_FALLBACK_TIMEOUT_MS = 5_000L

    // Latency & loaded latency configuration
    const val PING_REQUEST_COUNT = 10
    const val PING_PROBE_SPACING_MS = 25L
    const val MIN_JITTER_SAMPLES = 3
    const val LOADED_LATENCY_INTERVAL_MS = 500L
    const val MIN_LOADED_LATENCY_SAMPLES = 3

    // Download configuration
    const val DOWNLOAD_STREAM_COUNT = 6
    const val DOWNLOAD_DURATION_MS = 10_000L
    const val DOWNLOAD_LITE_DURATION_MS = 5_000L
    const val DOWNLOAD_WARMUP_MS = 1_500L
    const val DOWNLOAD_CHUNK_BYTES = 25_000_000L
    const val DOWNLOAD_BUFFER_BYTES = 32 * 1024

    // Upload configuration
    const val UPLOAD_STREAM_COUNT = 4
    const val UPLOAD_DURATION_MS = 10_000L
    const val UPLOAD_LITE_DURATION_MS = 5_000L
    const val UPLOAD_WARMUP_MS = 1_500L
    const val UPLOAD_INITIAL_CHUNK_BYTES = 128 * 1024L
    const val UPLOAD_MAX_CHUNK_BYTES = 4 * 1024 * 1024L
    const val UPLOAD_PAYLOAD_BUFFER_BYTES = 64 * 1024
    const val UPLOAD_WRITE_SLICE_BYTES = 16 * 1024

    // Live sampling & smoothing (~10 updates/sec)
    const val SAMPLE_INTERVAL_MS = 100L
    const val ROLLING_WINDOW_NANOS = 1_000_000_000L
    const val EMA_SMOOTHING_ALPHA = 0.35

    // OkHttp timeouts & pooling
    const val CONNECT_TIMEOUT_SECONDS = 8L
    const val READ_TIMEOUT_SECONDS = 10L
    const val WRITE_TIMEOUT_SECONDS = 10L
    const val PING_CALL_TIMEOUT_SECONDS = 6L
    const val PING_PROBE_TIMEOUT_MS = 2_000L
    const val LOADED_PING_CALL_TIMEOUT_SECONDS = 4L
    const val MAX_REQUESTS_PER_HOST = 16
    const val CONNECTION_POOL_SIZE = 12
    const val CONNECTION_KEEP_ALIVE_SECONDS = 30L
}
