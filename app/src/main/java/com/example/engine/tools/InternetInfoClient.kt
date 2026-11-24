package com.example.engine.tools

import com.example.data.constants.ToolsConstants
import com.example.engine.speedtest.OkHttpCleanup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class IpWhoIsResult(
    val publicIp: String?,
    val isp: String?,
)

data class InternetInfoResult(
    val publicIp: String?,
    val isp: String?,
    val ipv4Supported: Boolean,
    val ipv6Supported: Boolean,
)

/**
 * Looks up public IP, ISP name, and IPv4/IPv6 reachability with strict 4-second timeouts.
 * All external calls run on Dispatchers.IO, cancel cleanly off the main thread when the caller
 * leaves, propagate CancellationException without swallowing it as a UI error, and always close
 * their HTTP responses.
 */
class InternetInfoClient(
    private val baseClient: OkHttpClient = createDefaultClient(),
) {
    private val closed = AtomicBoolean(false)
    private val activeCalls = CopyOnWriteArraySet<Call>()

    private val noCache = CacheControl.Builder()
        .noCache()
        .noStore()
        .build()

    private val ipv4Client: OkHttpClient by lazy {
        baseClient.newBuilder()
            .dns(FilteredDns(preferIpv6 = false))
            .build()
    }

    private val ipv6Client: OkHttpClient by lazy {
        baseClient.newBuilder()
            .dns(FilteredDns(preferIpv6 = true))
            .build()
    }

    suspend fun fetchInternetInfo(): InternetInfoResult = withContext(Dispatchers.IO) {
        if (closed.get()) {
            throw CancellationException("InternetInfoClient is closed")
        }
        coroutineScope {
            val ipWhoIsDeferred = async { fetchIpAndIsp() }
            val ipv4Deferred = async { checkIpv4Support() }
            val ipv6Deferred = async { checkIpv6Support() }

            val ipWhoIs = ipWhoIsDeferred.await()
            val ipv4Ok = ipv4Deferred.await()
            val ipv6Ok = ipv6Deferred.await()

            ensureActive()
            if (closed.get()) {
                throw CancellationException("InternetInfoClient was closed during lookup")
            }

            InternetInfoResult(
                publicIp = ipWhoIs.publicIp,
                isp = ipWhoIs.isp,
                ipv4Supported = ipv4Ok,
                ipv6Supported = ipv6Ok,
            )
        }
    }

    private suspend fun fetchIpAndIsp(): IpWhoIsResult {
        val body = executeGet(baseClient, ToolsConstants.IP_INFO_ENDPOINT)
            ?: return IpWhoIsResult(publicIp = null, isp = null)
        return parseIpWhoIsJson(body)
    }

    private suspend fun checkIpv4Support(): Boolean {
        val body = executeGet(ipv4Client, ToolsConstants.IPV4_CHECK_ENDPOINT)?.trim()
            ?: return false
        return isLikelyIpv4(body)
    }

    private suspend fun checkIpv6Support(): Boolean {
        val body = executeGet(ipv6Client, ToolsConstants.IPV6_CHECK_ENDPOINT)?.trim()
            ?: return false
        return isLikelyIpv6(body)
    }

    private suspend fun executeGet(client: OkHttpClient, url: String): String? {
        currentCoroutineContext().ensureActive()
        if (closed.get()) {
            throw CancellationException("InternetInfoClient is closed")
        }

        val request = Request.Builder()
            .url(url)
            .cacheControl(noCache)
            .header("Accept", "application/json, text/plain")
            .header("Connection", "close")
            .get()
            .build()

        val call = client.newCall(request)
        activeCalls.add(call)
        if (closed.get()) {
            activeCalls.remove(call)
            OkHttpCleanup.cancelCallAsync(call)
            throw CancellationException("InternetInfoClient is closed")
        }

        // Cancel off the calling thread so cancelling from Main (e.g. ViewModel.onCleared)
        // never triggers SSLSocket.close() on the main thread.
        val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause != null) {
                OkHttpCleanup.cancelCallAsync(call)
            }
        }

        return try {
            call.execute().use { response ->
                if (call.isCanceled() || closed.get()) {
                    throw CancellationException("Internet lookup call cancelled")
                }
                currentCoroutineContext().ensureActive()
                if (!response.isSuccessful) return null
                response.body?.string()?.trim()?.takeIf { it.isNotBlank() }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: IOException) {
            if (call.isCanceled() || closed.get()) {
                throw CancellationException("Internet lookup call cancelled")
            }
            currentCoroutineContext().ensureActive()
            null
        } catch (_: Exception) {
            if (call.isCanceled() || closed.get()) {
                throw CancellationException("Internet lookup call cancelled")
            }
            currentCoroutineContext().ensureActive()
            null
        } finally {
            cancelHandle?.dispose()
            activeCalls.remove(call)
        }
    }

    /**
     * Cancels pending lookups and evicts pooled sockets strictly on a background thread.
     * Safe to invoke multiple times and safe to call from the Android main thread.
     */
    fun close() {
        val snapshotCalls = activeCalls.toList()
        activeCalls.clear()
        val firstClose = closed.compareAndSet(false, true)
        snapshotCalls.forEach { call ->
            OkHttpCleanup.cancelCallAsync(call)
        }
        if (firstClose) {
            OkHttpCleanup.closeClientAsync(baseClient, evictPool = true)
        }
    }

    private class FilteredDns(private val preferIpv6: Boolean) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = Dns.SYSTEM.lookup(hostname)
            val filtered = if (preferIpv6) {
                addresses.filterIsInstance<Inet6Address>()
            } else {
                addresses.filterIsInstance<Inet4Address>()
            }
            if (filtered.isEmpty()) {
                throw UnknownHostException("No ${if (preferIpv6) "IPv6" else "IPv4"} address for $hostname")
            }
            return filtered
        }
    }

    companion object {
        fun createDefaultClient(): OkHttpClient {
            val timeout = ToolsConstants.INTERNET_LOOKUP_TIMEOUT_SECONDS
            return OkHttpClient.Builder()
                .connectTimeout(timeout, TimeUnit.SECONDS)
                .readTimeout(timeout, TimeUnit.SECONDS)
                .writeTimeout(timeout, TimeUnit.SECONDS)
                .callTimeout(timeout, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .cache(null)
                .build()
        }

        internal fun parseIpWhoIsJson(rawJson: String): IpWhoIsResult {
            return runCatching {
                val json = JSONObject(rawJson)
                if (json.has("success") && !json.optBoolean("success", true)) {
                    return IpWhoIsResult(publicIp = null, isp = null)
                }
                val ip = json.optString("ip", "")
                    .trim()
                    .takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }

                val connection = json.optJSONObject("connection")
                val isp = listOf(
                    connection?.optString("isp", ""),
                    connection?.optString("org", ""),
                    json.optString("isp", ""),
                    json.optString("org", ""),
                )
                    .mapNotNull { it?.trim() }
                    .firstOrNull { it.isNotBlank() && !it.equals("null", ignoreCase = true) }

                IpWhoIsResult(publicIp = ip, isp = isp)
            }.getOrDefault(IpWhoIsResult(publicIp = null, isp = null))
        }

        internal fun isLikelyIpv4(value: String): Boolean {
            val parts = value.split('.')
            if (parts.size != 4) return false
            return parts.all { part ->
                part.isNotEmpty() && part.all(Char::isDigit) && (part.toIntOrNull() in 0..255)
            }
        }

        internal fun isLikelyIpv6(value: String): Boolean {
            if (!value.contains(':')) return false
            if (value.length !in 2..45) return false
            return value.all { ch -> ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F' || ch == ':' || ch == '.' }
        }
    }
}
