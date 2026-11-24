package com.example.engine.speedtest

import com.example.data.constants.SpeedTestConstants
import com.example.engine.server.SafeServerDns
import com.example.engine.server.SameHostHttpsRedirectInterceptor
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Thrown when a speed test phase receives or transmits zero bytes within the initial stall window
 * (e.g., 5 seconds), signaling that the engine should fall back to Cloudflare.
 */
class ServerNoDataTimeoutException(
    message: String = "Server returned no data within timeout",
) : IOException(message)

/**
 * Builds properly tuned OkHttpClient instances for parallel throughput streams,
 * single-connection unloaded ping, and separate-connection loaded latency probes.
 * All clients enforce public-routable DNS resolution and block cross-host or HTTP redirects.
 */
object SpeedTestClientFactory {

    /**
     * Throughput client configured for true parallel TCP streams (HTTP/1.1) and generous per-host
     * connection concurrency so 6 download streams or 4 upload streams run without queueing.
     */
    fun createThroughputClient(): OkHttpClient {
        val dispatcher = Dispatcher().apply {
            maxRequests = SpeedTestConstants.MAX_REQUESTS_PER_HOST * 2
            maxRequestsPerHost = SpeedTestConstants.MAX_REQUESTS_PER_HOST
        }
        val connectionPool = ConnectionPool(
            SpeedTestConstants.CONNECTION_POOL_SIZE,
            SpeedTestConstants.CONNECTION_KEEP_ALIVE_SECONDS,
            TimeUnit.SECONDS,
        )
        return OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(connectionPool)
            .dns(SafeServerDns)
            .protocols(listOf(Protocol.HTTP_1_1))
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(SameHostHttpsRedirectInterceptor())
            .connectTimeout(SpeedTestConstants.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(SpeedTestConstants.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(SpeedTestConstants.WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .cache(null)
            .build()
    }

    /**
     * Dedicated single-connection persistent client for unloaded ping/jitter measurement.
     * Negotiates HTTP/2 (with HTTP/1.1 fallback) so sequential ping probes reuse one warm
     * TCP/TLS connection reliably without HTTP/1.1 keep-alive drops or cold-handshake spikes.
     */
    fun createLatencyClient(): OkHttpClient {
        val connectionPool = ConnectionPool(1, 30, TimeUnit.SECONDS)
        return OkHttpClient.Builder()
            .connectionPool(connectionPool)
            .dns(SafeServerDns)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(SameHostHttpsRedirectInterceptor())
            .connectTimeout(SpeedTestConstants.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(SpeedTestConstants.PING_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(SpeedTestConstants.PING_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .cache(null)
            .build()
    }

    /**
     * Separate single-connection persistent client dedicated to loaded latency probes
     * running concurrently during download and upload phases.
     */
    fun createLoadedLatencyClient(): OkHttpClient {
        val connectionPool = ConnectionPool(1, 30, TimeUnit.SECONDS)
        return OkHttpClient.Builder()
            .connectionPool(connectionPool)
            .dns(SafeServerDns)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(SameHostHttpsRedirectInterceptor())
            .connectTimeout(SpeedTestConstants.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(SpeedTestConstants.LOADED_PING_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(SpeedTestConstants.LOADED_PING_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .cache(null)
            .build()
    }
}
