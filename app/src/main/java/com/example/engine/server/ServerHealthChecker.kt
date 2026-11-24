package com.example.engine.server

import com.example.data.constants.SpeedTestConstants
import com.example.data.server.CloudflareColoMapper
import com.example.data.server.ServerKind
import com.example.data.server.ServerProfile
import com.example.engine.speedtest.SpeedMath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ServerProbeOutcome(
    val profile: ServerProfile,
    val medianPingMs: Double?,
    val isHealthy: Boolean,
)

/**
 * Probes server candidates concurrently (up to 8 at a time), 3 ping requests each with a 2-second
 * timeout, drops unresponsive servers, extracts Cloudflare `cf-ray` location, and ranks by median ping.
 */
class ServerHealthChecker(
    private val probeClient: OkHttpClient = createProbeClient(),
    private val singlePingOverride: (suspend (ServerProfile) -> Pair<Double?, String?>)? = null,
) {

    private val noStoreCacheControl = CacheControl.Builder()
        .noStore()
        .noCache()
        .build()

    /**
     * Probes all [candidates] concurrently (bounded by [maxConcurrency]) and emits each
     * [ServerProbeOutcome] progressively as soon as it finishes so UI stays responsive.
     */
    fun probeCandidatesProgressive(
        candidates: List<ServerProfile>,
        maxConcurrency: Int = SpeedTestConstants.SERVER_HEALTH_CONCURRENCY,
        probeCount: Int = SpeedTestConstants.SERVER_HEALTH_PROBES,
        timeoutMs: Long = SpeedTestConstants.SERVER_HEALTH_TIMEOUT_MS,
    ): Flow<ServerProbeOutcome> = channelFlow {
        val semaphore = Semaphore(maxConcurrency.coerceAtLeast(1))
        coroutineScope {
            candidates.map { candidate ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val outcome = probeServer(
                            server = candidate,
                            probeCount = probeCount,
                            timeoutMs = timeoutMs,
                        )
                        send(outcome)
                    }
                }
            }.awaitAll()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Probes a single server with [probeCount] sequential pings (each capped at [timeoutMs]).
     * Returns `isHealthy = false` if no ping succeeded.
     */
    suspend fun probeServer(
        server: ServerProfile,
        probeCount: Int = SpeedTestConstants.SERVER_HEALTH_PROBES,
        timeoutMs: Long = SpeedTestConstants.SERVER_HEALTH_TIMEOUT_MS,
    ): ServerProbeOutcome = withContext(Dispatchers.IO) {
        val rtts = ArrayList<Double>(probeCount)
        var discoveredCfLocation: String? = null

        for (attempt in 0 until probeCount) {
            if (!isActive) throw CancellationException("Health probe cancelled")
            val (rttMs, cfLocation) = executeSinglePing(server, attempt, timeoutMs)
            if (cfLocation != null) {
                discoveredCfLocation = cfLocation
            }
            if (rttMs != null && rttMs.isFinite() && rttMs > 0.0) {
                rtts.add(rttMs)
            }
        }

        val updatedProfile = if (
            server.kind == ServerKind.CLOUDFLARE &&
            !discoveredCfLocation.isNullOrBlank()
        ) {
            server.copy(location = discoveredCfLocation)
        } else {
            server
        }

        val median = SpeedMath.calculateMedian(rtts)
        ServerProbeOutcome(
            profile = updatedProfile,
            medianPingMs = median,
            isHealthy = median != null,
        )
    }

    private suspend fun executeSinglePing(
        server: ServerProfile,
        attempt: Int,
        timeoutMs: Long,
    ): Pair<Double?, String?> {
        if (singlePingOverride != null) {
            return try {
                singlePingOverride.invoke(server)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                null to null
            }
        }

        val url = server.pingUrl(System.nanoTime() + attempt)
        val request = Request.Builder()
            .url(url)
            .cacheControl(noStoreCacheControl)
            .header("Accept-Encoding", "identity")
            .get()
            .build()

        val callClient = if (timeoutMs == SpeedTestConstants.SERVER_HEALTH_TIMEOUT_MS) {
            probeClient
        } else {
            probeClient.newBuilder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
        }

        val startNanos = System.nanoTime()
        return try {
            callClient.newCall(request).execute().use { response ->
                val cfLocation = if (server.kind == ServerKind.CLOUDFLARE) {
                    CloudflareColoMapper.locationFromCfRay(response.header("cf-ray"))
                } else {
                    null
                }
                if (!response.isSuccessful) {
                    return null to cfLocation
                }
                ServerUrlValidator.drainBounded(response.body, ServerUrlValidator.MAX_PING_RESPONSE_BYTES)
                val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000.0
                elapsedMs to cfLocation
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: IOException) {
            null to null
        } catch (_: IllegalArgumentException) {
            null to null
        }
    }

    fun cancelAll() {
        probeClient.dispatcher.cancelAll()
    }

    companion object {
        fun createProbeClient(): OkHttpClient {
            val dispatcher = Dispatcher().apply {
                maxRequests = 16
                maxRequestsPerHost = 4
            }
            val connectionPool = ConnectionPool(12, 15, TimeUnit.SECONDS)
            return OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .connectionPool(connectionPool)
                .dns(SafeServerDns)
                .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .followRedirects(false)
                .followSslRedirects(false)
                .addInterceptor(SameHostHttpsRedirectInterceptor())
                .connectTimeout(SpeedTestConstants.SERVER_HEALTH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(SpeedTestConstants.SERVER_HEALTH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(SpeedTestConstants.SERVER_HEALTH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .cache(null)
                .build()
        }

        /**
         * Drops unresponsive servers and ranks healthy servers by median ping ascending.
         */
        fun rankHealthyServers(outcomes: List<ServerProbeOutcome>): List<ServerProbeOutcome> =
            outcomes
                .filter { it.isHealthy && it.medianPingMs != null }
                .sortedWith(
                    compareBy<ServerProbeOutcome> { it.medianPingMs ?: Double.MAX_VALUE }
                        .thenBy { if (it.profile.kind == ServerKind.CLOUDFLARE) 0 else 1 }
                        .thenBy { it.profile.name.lowercase() },
                )

        /**
         * Selects the lowest-ping healthy server, always falling back to Cloudflare if none responded.
         */
        fun selectAutoFastest(
            outcomes: List<ServerProbeOutcome>,
            fallbackCloudflare: ServerProfile = ServerProfile.CLOUDFLARE,
        ): ServerProfile = rankHealthyServers(outcomes).firstOrNull()?.profile ?: fallbackCloudflare
    }
}
