package com.example.data.models

import androidx.compose.runtime.Immutable

/**
 * Future-ready speed test result model.
 * Any unavailable measurement is represented as null, never 0.
 */
@Immutable
data class TestResult(
    val id: Long = 0L,
    val timestamp: Long,
    val connectionType: ConnectionType,
    val ping: Double?,
    val jitter: Double?,
    val download: Double?,
    val upload: Double?,
    val downloadLoadedLatency: Double? = null,
    val uploadLoadedLatency: Double? = null,
    val bufferbloatGrade: BufferbloatGrade? = null,
    val networkName: String? = null,
    val cellularNetworkTag: String? = null,
    val deviceModel: String? = null,
    val wifiBand: String? = null,
)
