package com.example.data.models

import androidx.compose.runtime.Immutable

enum class ConnectionType {
    WIFI,
    MOBILE,
    ETHERNET,
    NONE,
}

enum class TestStage {
    IDLE,
    PING,
    DOWNLOAD,
    UPLOAD,
    COMPLETED,
}

@Immutable
data class SpeedSample(
    val elapsedSeconds: Float,
    val speedMbps: Double,
)

sealed interface SpeedTestError {
    data object NoInternet : SpeedTestError
    data object Timeout : SpeedTestError
    data object ServerUnreachable : SpeedTestError
    data object NetworkLost : SpeedTestError
    data object NetworkChanged : SpeedTestError
    data object Unknown : SpeedTestError
}

sealed interface SpeedTestProgress {
    data class StageStarted(val stage: TestStage) : SpeedTestProgress

    data class PingSampled(
        val completedCount: Int,
        val totalCount: Int,
        val latestPingMs: Double,
        val currentMedianPingMs: Double,
    ) : SpeedTestProgress

    data class PingCompleted(
        val pingMs: Double?,
        val jitterMs: Double?,
    ) : SpeedTestProgress

    data class ThroughputUpdate(
        val stage: TestStage,
        val currentSpeedMbps: Double,
        val sample: SpeedSample,
        val isWarmingUp: Boolean,
        val progressFraction: Float,
    ) : SpeedTestProgress

    data class LoadedLatencySampled(
        val stage: TestStage,
        val medianLatencyMs: Double,
    ) : SpeedTestProgress

    data class DownloadCompleted(
        val downloadMbps: Double?,
        val loadedLatencyMs: Double? = null,
    ) : SpeedTestProgress

    data class UploadCompleted(
        val uploadMbps: Double?,
        val loadedLatencyMs: Double? = null,
    ) : SpeedTestProgress

    data class Completed(val result: TestResult) : SpeedTestProgress

    data class Failed(val error: SpeedTestError) : SpeedTestProgress
}
