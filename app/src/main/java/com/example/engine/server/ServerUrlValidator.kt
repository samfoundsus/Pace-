package com.example.engine.server

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Enforces HTTPS-only, credential-free, public-routable URLs for all speed test servers,
 * blocks private/link-local/loopback hosts both syntactically and after DNS resolution,
 * blocks cross-host redirects, and bounds untrusted response reads.
 */
object ServerUrlValidator {

    const val MAX_COMMUNITY_LIST_BYTES: Long = 512L * 1024L
    const val MAX_PING_RESPONSE_BYTES: Long = 64L * 1024L

    private val blockedHostSuffixes = listOf(
        ".localhost",
        ".local",
        ".internal",
        ".home.arpa",
        ".lan",
    )

    /**
     * Converts scheme-relative URLs ("//host/path") into "https://host/path".
     */
    fun normalizeSchemeRelative(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.startsWith("//")) "https:$trimmed" else trimmed
    }

    /**
     * Validates a standalone server URL:
     * - Scheme-relative URLs are upgraded to https.
     * - Only https is accepted.
     * - Userinfo credentials (user:pass@) are rejected.
     * - localhost and private, link-local, loopback, unspecified, CGNAT, or multicast IP literals are rejected.
     */
    fun validateHttpsUrl(rawUrl: String): String? {
        val normalized = normalizeSchemeRelative(rawUrl)
        if (normalized.isEmpty()) return null
        val parsed = normalized.toHttpUrlOrNull() ?: return null
        if (!isSafeHttpUrl(parsed)) return null
        return parsed.toString()
    }

    /**
     * Resolves an endpoint path or URL (e.g., "garbage.php", "backend/empty.php", "/empty.php",
     * "//host/empty.php", or "https://host/empty.php") against a validated base URL.
     * Ensures base URLs without a trailing slash (like "https://host/backend") preserve their
     * final path segment when joining relative paths like "garbage.php".
     */
    fun resolveEndpointUrl(baseUrl: String, pathOrUrl: String): String? {
        val normalizedBase = validateHttpsUrl(baseUrl) ?: return null
        val baseHttpUrl = normalizedBase.toHttpUrlOrNull() ?: return null

        val trimmedPath = pathOrUrl.trim()
        if (trimmedPath.isEmpty()) return null

        val candidateString = normalizeSchemeRelative(trimmedPath)
        val resolved: HttpUrl = when {
            candidateString.startsWith("https://", ignoreCase = true) ||
                candidateString.startsWith("http://", ignoreCase = true) -> {
                candidateString.toHttpUrlOrNull() ?: return null
            }
            candidateString.startsWith("/") -> {
                baseHttpUrl.resolve(candidateString) ?: return null
            }
            else -> {
                // Ensure relative path appends beneath base path segment even if base lacks a trailing slash
                val baseDir = if (normalizedBase.endsWith("/")) normalizedBase else "$normalizedBase/"
                val baseDirUrl = baseDir.toHttpUrlOrNull() ?: return null
                baseDirUrl.resolve(candidateString) ?: return null
            }
        }

        if (!isSafeHttpUrl(resolved)) return null
        return resolved.toString()
    }

    fun isSafeHttpUrl(url: HttpUrl): Boolean {
        if (!url.isHttps) return false
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return false
        val host = url.host.trim().lowercase()
        if (host.isEmpty()) return false
        if (host == "localhost" || blockedHostSuffixes.any { host.endsWith(it) }) return false

        // Reject literal IP addresses that belong to loopback, private, link-local, or reserved ranges.
        val unbracketed = host.removePrefix("[").removeSuffix("]")
        val literalBytes = parseIpLiteralOrNull(unbracketed)
        if (literalBytes != null) {
            val addr = runCatching { InetAddress.getByAddress(literalBytes) }.getOrNull() ?: return false
            if (isUnsafeInetAddress(addr)) return false
        }
        return true
    }

    /**
     * Checks whether a resolved [InetAddress] points to loopback, private (RFC 1918 / ULA),
     * link-local, unspecified, CGNAT, multicast, or documentation/reserved space.
     */
    fun isUnsafeInetAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }

        return when (address) {
            is Inet4Address -> isUnsafeIpv4Bytes(address.address)
            is Inet6Address -> isUnsafeIpv6Bytes(address.address)
            else -> true
        }
    }

    private fun isUnsafeIpv4Bytes(bytes: ByteArray): Boolean {
        if (bytes.size != 4) return true
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        val b2 = bytes[2].toInt() and 0xFF
        return when {
            b0 == 0 -> true // 0.0.0.0/8 ("This network")
            b0 == 10 -> true // 10.0.0.0/8 private
            b0 == 100 && b1 in 64..127 -> true // 100.64.0.0/10 shared/CGNAT
            b0 == 127 -> true // 127.0.0.0/8 loopback
            b0 == 169 && b1 == 254 -> true // 169.254.0.0/16 link-local
            b0 == 172 && b1 in 16..31 -> true // 172.16.0.0/12 private
            b0 == 192 && b1 == 0 && b2 == 0 -> true // 192.0.0.0/24 IETF protocol assignments
            b0 == 192 && b1 == 0 && b2 == 2 -> true // 192.0.2.0/24 TEST-NET-1
            b0 == 192 && b1 == 168 -> true // 192.168.0.0/16 private
            b0 == 198 && (b1 == 18 || b1 == 19) -> true // 198.18.0.0/15 benchmarking
            b0 == 198 && b1 == 51 && b2 == 100 -> true // 198.51.100.0/24 TEST-NET-2
            b0 == 203 && b1 == 0 && b2 == 113 -> true // 203.0.113.0/24 TEST-NET-3
            b0 >= 224 -> true // 224.0.0.0/4 multicast & 240.0.0.0/4 reserved / broadcast
            else -> false
        }
    }

    private fun isUnsafeIpv6Bytes(bytes: ByteArray): Boolean {
        if (bytes.size != 16) return true
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF

        // All zeros (::) or loopback (::1)
        val first15Zero = (0 until 15).all { bytes[it].toInt() == 0 }
        if (first15Zero && (bytes[15].toInt() == 0 || bytes[15].toInt() == 1)) return true

        // Unique local address fc00::/7
        if ((b0 and 0xFE) == 0xFC) return true

        // Link-local fe80::/10
        if (b0 == 0xFE && (b1 and 0xC0) == 0x80) return true

        // Multicast ff00::/8
        if (b0 == 0xFF) return true

        // IPv4-mapped IPv6 ::ffff:a.b.c.d
        val isIpv4Mapped = (0 until 10).all { bytes[it].toInt() == 0 } &&
            (bytes[10].toInt() and 0xFF) == 0xFF &&
            (bytes[11].toInt() and 0xFF) == 0xFF
        if (isIpv4Mapped) {
            return isUnsafeIpv4Bytes(bytes.copyOfRange(12, 16))
        }

        return false
    }

    /**
     * Parses a numeric IPv4 or IPv6 literal without performing any DNS network lookup.
     */
    private fun parseIpLiteralOrNull(host: String): ByteArray? {
        if (host.contains(':')) {
            return parseIpv6Literal(host)
        }
        val parts = host.split('.')
        if (parts.size == 4 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) }) {
            val out = ByteArray(4)
            for (i in 0 until 4) {
                val value = parts[i].toIntOrNull() ?: return null
                if (value !in 0..255) return null
                out[i] = value.toByte()
            }
            return out
        }
        // Single decimal/hex integer IPv4 (e.g., 2130706433 or 0x7f000001)
        if (parts.size == 1 && (host.all(Char::isDigit) || host.startsWith("0x"))) {
            val numeric = if (host.startsWith("0x")) {
                host.substring(2).toLongOrNull(16)
            } else {
                host.toLongOrNull(10)
            } ?: return null
            if (numeric in 0L..0xFFFFFFFFL) {
                return byteArrayOf(
                    ((numeric shr 24) and 0xFF).toByte(),
                    ((numeric shr 16) and 0xFF).toByte(),
                    ((numeric shr 8) and 0xFF).toByte(),
                    (numeric and 0xFF).toByte(),
                )
            }
        }
        return null
    }

    private fun parseIpv6Literal(raw: String): ByteArray? {
        val zoneStripped = raw.substringBefore('%')
        val hasDoubleColon = zoneStripped.contains("::")
        if (zoneStripped.indexOf("::") != zoneStripped.lastIndexOf("::")) return null

        // Handle IPv4 tail in IPv6 like ::ffff:127.0.0.1
        val normalized = if (zoneStripped.contains('.')) {
            val lastColon = zoneStripped.lastIndexOf(':')
            if (lastColon < 0) return null
            val ipv4Part = zoneStripped.substring(lastColon + 1)
            val ipv4Bytes = parseIpLiteralOrNull(ipv4Part) ?: return null
            if (ipv4Bytes.size != 4) return null
            val high = ((ipv4Bytes[0].toInt() and 0xFF) shl 8) or (ipv4Bytes[1].toInt() and 0xFF)
            val low = ((ipv4Bytes[2].toInt() and 0xFF) shl 8) or (ipv4Bytes[3].toInt() and 0xFF)
            "${zoneStripped.substring(0, lastColon)}:${high.toString(16)}:${low.toString(16)}"
        } else {
            zoneStripped
        }

        val halves = if (hasDoubleColon) normalized.split("::") else listOf(normalized)
        val leftGroups = if (halves[0].isEmpty()) emptyList() else halves[0].split(':')
        val rightGroups = if (halves.size == 2 && halves[1].isNotEmpty()) halves[1].split(':') else emptyList()

        val totalExplicit = leftGroups.size + rightGroups.size
        if (hasDoubleColon && totalExplicit > 8) return null
        if (!hasDoubleColon && totalExplicit != 8) return null

        val zeroFill = 8 - totalExplicit
        val allGroups = ArrayList<Int>(8)
        for (g in leftGroups) {
            val v = g.toIntOrNull(16) ?: return null
            if (v !in 0..0xFFFF) return null
            allGroups.add(v)
        }
        repeat(zeroFill) { allGroups.add(0) }
        for (g in rightGroups) {
            val v = g.toIntOrNull(16) ?: return null
            if (v !in 0..0xFFFF) return null
            allGroups.add(v)
        }
        if (allGroups.size != 8) return null

        val out = ByteArray(16)
        for (i in 0 until 8) {
            out[i * 2] = ((allGroups[i] shr 8) and 0xFF).toByte()
            out[i * 2 + 1] = (allGroups[i] and 0xFF).toByte()
        }
        return out
    }

    /**
     * Reads at most [maxBytes] from [body] as UTF-8, throwing [IOException] if the response exceeds the cap.
     */
    @Throws(IOException::class)
    fun readBoundedUtf8(body: ResponseBody, maxBytes: Long): String {
        val source = body.source()
        val output = ByteArrayOutputStream()
        val buffer = Buffer()
        var totalRead = 0L
        while (true) {
            val read = source.read(buffer, 8_192L)
            if (read == -1L) break
            totalRead += read
            if (totalRead > maxBytes) {
                throw IOException("Response exceeded size limit of $maxBytes bytes")
            }
            buffer.copyTo(output)
            buffer.clear()
        }
        return output.toString(Charsets.UTF_8.name())
    }

    /**
     * Drains up to [maxBytes] from a ping response body so OkHttp can reuse the socket
     * while preventing unbounded memory or bandwidth consumption.
     */
    fun drainBounded(body: ResponseBody?, maxBytes: Long = MAX_PING_RESPONSE_BYTES) {
        if (body == null) return
        val source = body.source()
        val buffer = Buffer()
        var drained = 0L
        while (drained < maxBytes) {
            val toRead = minOf(8_192L, maxBytes - drained)
            val read = source.read(buffer, toRead)
            if (read == -1L) break
            buffer.clear()
            drained += read
        }
    }
}

/**
 * Custom OkHttp [Dns] wrapper that refuses to connect if DNS resolves a hostname
 * to a loopback, private, link-local, unspecified, or reserved address.
 */
object SafeServerDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val cleanHost = hostname.trim().lowercase()
        if (cleanHost == "localhost" || cleanHost.endsWith(".localhost") || cleanHost.endsWith(".local")) {
            throw UnknownHostException("Blocked non-public host: $hostname")
        }
        val resolved = Dns.SYSTEM.lookup(hostname)
        val safeAddresses = resolved.filterNot(ServerUrlValidator::isUnsafeInetAddress)
        if (safeAddresses.isEmpty()) {
            throw UnknownHostException("Resolved address is not a public routable IP: $hostname")
        }
        return safeAddresses
    }
}

/**
 * Application interceptor used alongside `followRedirects(false)` and `followSslRedirects(false)`.
 * Follows redirects only when the destination stays on HTTPS and the exact same host,
 * has no embedded credentials, and passes [ServerUrlValidator]. Cross-host redirects are never followed.
 */
class SameHostHttpsRedirectInterceptor(
    private val maxRedirects: Int = 3,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var currentRequest = chain.request()
        var response = chain.proceed(currentRequest)
        var redirectCount = 0

        while (response.isRedirect && redirectCount < maxRedirects) {
            val location = response.header("Location")?.trim() ?: break
            val targetUrl = currentRequest.url.resolve(location) ?: break

            val sameHost = targetUrl.host.equals(currentRequest.url.host, ignoreCase = true)
            if (!sameHost || !ServerUrlValidator.isSafeHttpUrl(targetUrl)) {
                // Do not follow cross-host, HTTP downgrade, or unsafe redirects.
                break
            }

            val preserveMethod = response.code == 307 || response.code == 308
            val nextRequestBuilder = currentRequest.newBuilder().url(targetUrl)
            if (!preserveMethod && currentRequest.method != "GET" && currentRequest.method != "HEAD") {
                nextRequestBuilder.method("GET", null)
                    .removeHeader("Content-Length")
                    .removeHeader("Content-Type")
                    .removeHeader("Transfer-Encoding")
            }

            response.close()
            currentRequest = nextRequestBuilder.build()
            response = chain.proceed(currentRequest)
            redirectCount++
        }

        return response
    }
}
