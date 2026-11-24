package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.data.models.BufferbloatGrade
import com.example.data.models.ConnectionType
import com.example.data.models.TestResult

@Entity(
    tableName = "test_results",
    indices = [Index(value = ["timestamp"], unique = true)],
)
data class TestResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestamp: Long,
    val connectionType: String,
    val ping: Double?,
    val jitter: Double?,
    val download: Double?,
    val upload: Double?,
    val downloadLoadedLatency: Double?,
    val uploadLoadedLatency: Double?,
    val bufferbloatGrade: String?,
    val networkName: String? = null,
    val cellularNetworkTag: String? = null,
    val deviceModel: String? = null,
    val wifiBand: String? = null,
    val serverName: String? = null,
    val serverLocation: String? = null,
)

fun TestResultEntity.toDomain(): TestResult {
    val resolvedConnectionType = runCatching { ConnectionType.valueOf(connectionType) }
        .getOrDefault(ConnectionType.NONE)
    val resolvedWifiBand = wifiBand?.takeIf { it.isNotBlank() }
        ?: if (resolvedConnectionType == ConnectionType.WIFI) {
            cellularNetworkTag?.takeIf { it.isNotBlank() }
        } else {
            null
        }
    return TestResult(
        id = id,
        timestamp = timestamp,
        connectionType = resolvedConnectionType,
        ping = ping,
        jitter = jitter,
        download = download,
        upload = upload,
        downloadLoadedLatency = downloadLoadedLatency,
        uploadLoadedLatency = uploadLoadedLatency,
        bufferbloatGrade = bufferbloatGrade?.let { name ->
            runCatching { BufferbloatGrade.valueOf(name) }.getOrNull()
        },
        networkName = networkName,
        cellularNetworkTag = cellularNetworkTag,
        deviceModel = deviceModel,
        wifiBand = resolvedWifiBand,
        serverName = serverName?.takeIf { it.isNotBlank() },
        serverLocation = serverLocation?.takeIf { it.isNotBlank() },
    )
}

fun TestResult.toEntity(): TestResultEntity = TestResultEntity(
    id = id,
    timestamp = timestamp,
    connectionType = connectionType.name,
    ping = ping,
    jitter = jitter,
    download = download,
    upload = upload,
    downloadLoadedLatency = downloadLoadedLatency,
    uploadLoadedLatency = uploadLoadedLatency,
    bufferbloatGrade = bufferbloatGrade?.name,
    networkName = networkName,
    cellularNetworkTag = cellularNetworkTag,
    deviceModel = deviceModel,
    wifiBand = wifiBand,
    serverName = serverName?.takeIf { it.isNotBlank() },
    serverLocation = serverLocation?.takeIf { it.isNotBlank() },
)
