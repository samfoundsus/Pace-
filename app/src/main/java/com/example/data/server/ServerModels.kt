package com.example.data.server

import androidx.compose.runtime.Immutable

enum class ServerKind {
    CLOUDFLARE,
    COMMUNITY,
    CUSTOM,
}

enum class ServerProbeStatus {
    PENDING,
    PROBING,
    HEALTHY,
    UNAVAILABLE,
}

/**
 * Immutable description of a speed test server and its endpoint URL builders.
 * Every speed test phase (ping, download, upload, loaded latency) reads URLs strictly
 * from the active [ServerProfile] rather than hard-coding endpoints in engines.
 */
@Immutable
data class ServerProfile(
    val id: String,
    val name: String,
    val location: String = "",
    val kind: ServerKind,
    val baseUrl: String,
    val downloadBaseUrl: String,
    val uploadBaseUrl: String,
    val pingBaseUrl: String,
    val getIpBaseUrl: String? = null,
) {
    fun downloadUrl(bytes: Long, cacheBuster: Long): String = when (kind) {
        ServerKind.CLOUDFLARE -> appendQuery(downloadBaseUrl, "bytes=$bytes&r=$cacheBuster")
        ServerKind.COMMUNITY, ServerKind.CUSTOM -> appendQuery(
            downloadBaseUrl,
            "ckSize=$LIBRESPEED_DOWNLOAD_CHUNK_MB&r=$cacheBuster",
        )
    }

    fun uploadUrl(cacheBuster: Long): String = appendQuery(uploadBaseUrl, "r=$cacheBuster")

    fun pingUrl(cacheBuster: Long): String = when (kind) {
        ServerKind.CLOUDFLARE -> appendQuery(pingBaseUrl, "bytes=0&r=$cacheBuster")
        ServerKind.COMMUNITY, ServerKind.CUSTOM -> appendQuery(pingBaseUrl, "r=$cacheBuster")
    }

    fun displayNameWithLocation(): String {
        val cleanLocation = location.trim()
        return if (cleanLocation.isNotEmpty()) "$name · $cleanLocation" else name
    }

    companion object {
        const val AUTO_SERVER_ID = "auto"
        const val CLOUDFLARE_ID = "cloudflare"
        const val LIBRESPEED_DOWNLOAD_CHUNK_MB = 100

        const val DEFAULT_LIBRESPEED_DL_PATH = "garbage.php"
        const val DEFAULT_LIBRESPEED_UL_PATH = "empty.php"
        const val DEFAULT_LIBRESPEED_PING_PATH = "empty.php"
        const val DEFAULT_LIBRESPEED_GET_IP_PATH = "getIP.php"

        val CLOUDFLARE = ServerProfile(
            id = CLOUDFLARE_ID,
            name = "Cloudflare",
            location = "",
            kind = ServerKind.CLOUDFLARE,
            baseUrl = "https://speed.cloudflare.com",
            downloadBaseUrl = "https://speed.cloudflare.com/__down",
            uploadBaseUrl = "https://speed.cloudflare.com/__up",
            pingBaseUrl = "https://speed.cloudflare.com/__down",
            getIpBaseUrl = null,
        )

        private fun appendQuery(url: String, query: String): String {
            val separator = if ('?' in url) '&' else '?'
            return "$url$separator$query"
        }
    }
}

/**
 * Extracts Cloudflare edge location from the `cf-ray` response header.
 */
object CloudflareColoMapper {
    private val indianColos: Map<String, String> = mapOf(
        "BOM" to "Mumbai",
        "DEL" to "Delhi",
        "MAA" to "Chennai",
        "BLR" to "Bangalore",
        "HYD" to "Hyderabad",
        "CCU" to "Kolkata",
    )

    fun locationFromCfRay(cfRayHeader: String?): String? {
        val raw = cfRayHeader?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val dashIndex = raw.lastIndexOf('-')
        if (dashIndex < 0 || dashIndex >= raw.lastIndex) return null
        val code = raw.substring(dashIndex + 1).trim().uppercase()
        if (code.length !in 2..6 || !code.all { it in 'A'..'Z' || it in '0'..'9' }) {
            return null
        }
        return indianColos[code] ?: code
    }
}

@Immutable
data class ServerCandidateState(
    val profile: ServerProfile,
    val status: ServerProbeStatus = ServerProbeStatus.PENDING,
    val medianPingMs: Double? = null,
)

@Immutable
data class ServerSelectorUiState(
    val selectedServerId: String = ServerProfile.AUTO_SERVER_ID,
    val autoFastestServer: ServerProfile? = null,
    val autoFastestPingMs: Double? = null,
    val candidates: List<ServerCandidateState> = listOf(
        ServerCandidateState(profile = ServerProfile.CLOUDFLARE),
    ),
    val isProbing: Boolean = false,
    val hasProbedThisSession: Boolean = false,
    val communityLoadError: Boolean = false,
) {
    val isAutoSelected: Boolean
        get() = selectedServerId == ServerProfile.AUTO_SERVER_ID

    val selectedManualProfile: ServerProfile?
        get() = if (isAutoSelected) {
            null
        } else {
            candidates.firstOrNull { it.profile.id == selectedServerId }?.profile
        }
}
