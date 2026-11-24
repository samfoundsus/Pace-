package com.example.engine.server

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import com.example.data.models.SpeedTestProgress
import com.example.data.server.CloudflareColoMapper
import com.example.data.server.ServerKind
import com.example.data.server.ServerProfile
import com.example.engine.download.DownloadEngine
import com.example.engine.latency.LatencyEngine
import com.example.engine.speedtest.NetworkMonitor
import com.example.engine.speedtest.SpeedTestEngine
import com.example.engine.upload.UploadEngine
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import okio.source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.io.IOException
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ServerSelectionTest {

    @Test
    fun parser_acceptsSchemeRelativeAndIgnoresUnknownFieldsAndDropsUnsafeOrIncompleteEntries() {
        val feedJson = """
            [
              {
                "id": 51,
                "name": "Amsterdam, Netherlands (Clouvider)",
                "server": "//ams.speedtest.clouvider.net/backend",
                "dlURL": "garbage.php",
                "ulURL": "empty.php",
                "pingURL": "empty.php",
                "getIpURL": "getIP.php",
                "sponsorName": "Clouvider",
                "unknownExtraField": {"nested": [1, 2, 3]}
              },
              {
                "id": 82,
                "name": "Tokyo, Japan (A573)",
                "server": "https://librespeed.a573.net/",
                "dlURL": "backend/garbage.php",
                "ulURL": "backend/empty.php",
                "pingURL": "backend/empty.php"
              },
              {
                "id": 999,
                "name": "Missing Ping URL",
                "server": "https://valid.example.com/",
                "dlURL": "garbage.php",
                "ulURL": "empty.php"
              },
              {
                "id": 1000,
                "name": "Plain HTTP Server",
                "server": "http://insecure.example.com/",
                "dlURL": "garbage.php",
                "ulURL": "empty.php",
                "pingURL": "empty.php"
              },
              {
                "id": 1001,
                "name": "Credentials In URL",
                "server": "https://user:pass@cred.example.com/",
                "dlURL": "garbage.php",
                "ulURL": "empty.php",
                "pingURL": "empty.php"
              },
              {
                "id": 1002,
                "name": "Localhost Server",
                "server": "https://localhost:8443/",
                "dlURL": "garbage.php",
                "ulURL": "empty.php",
                "pingURL": "empty.php"
              },
              {
                "id": 1003,
                "name": "Loopback IPv4",
                "server": "https://127.0.0.1/",
                "dlURL": "garbage.php",
                "ulURL": "empty.php",
                "pingURL": "empty.php"
              },
              {
                "id": 1004,
                "name": "Private 10.x",
                "server": "https://10.0.0.42/",
                "dlURL": "garbage.php",
                "ulURL": "empty.php",
                "pingURL": "empty.php"
              },
              {
                "id": 1005,
                "name": "Private 192.168.x",
                "server": "https://192.168.1.50/",
                "dlURL": "garbage.php",
                "ulURL": "empty.php",
                "pingURL": "empty.php"
              },
              {
                "id": 1006,
                "name": "Private 172.16.x",
                "server": "https://172.16.8.1/",
                "dlURL": "garbage.php",
                "ulURL": "empty.php",
                "pingURL": "empty.php"
              },
              {
                "id": 1007,
                "name": "Link Local IPv4",
                "server": "https://169.254.169.254/",
                "dlURL": "garbage.php",
                "ulURL": "empty.php",
                "pingURL": "empty.php"
              },
              {
                "id": 1008,
                "name": "Loopback IPv6",
                "server": "https://[::1]/",
                "dlURL": "garbage.php",
                "ulURL": "empty.php",
                "pingURL": "empty.php"
              },
              {
                "id": 1009,
                "name": "ULA IPv6",
                "server": "https://[fc00::1]/",
                "dlURL": "garbage.php",
                "ulURL": "empty.php",
                "pingURL": "empty.php"
              },
              {
                "id": 1010,
                "name": "Link Local IPv6",
                "server": "https://[fe80::1]/",
                "dlURL": "garbage.php",
                "ulURL": "empty.php",
                "pingURL": "empty.php"
              },
              "not-an-object"
            ]
        """.trimIndent()

        val servers = LibreSpeedServerParser.parseCommunityServers(feedJson)
        assertEquals(2, servers.size)

        val ams = servers[0]
        assertEquals("community_51", ams.id)
        assertEquals("Clouvider", ams.name)
        assertEquals("Amsterdam, Netherlands", ams.location)
        assertEquals(ServerKind.COMMUNITY, ams.kind)
        assertEquals("https://ams.speedtest.clouvider.net/backend/garbage.php", ams.downloadBaseUrl)
        assertEquals("https://ams.speedtest.clouvider.net/backend/empty.php", ams.uploadBaseUrl)
        assertEquals("https://ams.speedtest.clouvider.net/backend/empty.php", ams.pingBaseUrl)
        assertEquals(
            "https://ams.speedtest.clouvider.net/backend/garbage.php?ckSize=100&r=123",
            ams.downloadUrl(bytes = 25_000_000L, cacheBuster = 123L),
        )
        assertEquals(
            "https://ams.speedtest.clouvider.net/backend/empty.php?r=456",
            ams.pingUrl(cacheBuster = 456L),
        )

        val tokyo = servers[1]
        assertEquals("community_82", tokyo.id)
        assertEquals("A573", tokyo.name)
        assertEquals("Tokyo, Japan", tokyo.location)
        assertEquals("https://librespeed.a573.net/backend/garbage.php", tokyo.downloadBaseUrl)
    }

    @Test
    fun validator_blocksCrossHostRedirectsAndCapsResponseSize() {
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(SameHostHttpsRedirectInterceptor())
            .addInterceptor(
                Interceptor { chain ->
                    val url = chain.request().url.toString()
                    when {
                        url.contains("/cross") -> Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(302)
                            .message("Found")
                            .header("Location", "https://evil.example.org/payload")
                            .body("".toResponseBody("text/plain".toMediaType()))
                            .build()
                        url.contains("/same-start") -> Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(302)
                            .message("Found")
                            .header("Location", "https://good.example.com/same-final")
                            .body("".toResponseBody("text/plain".toMediaType()))
                            .build()
                        else -> Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("ok".toResponseBody("text/plain".toMediaType()))
                            .build()
                    }
                },
            )
            .build()

        // Cross-host redirect must NOT be followed
        client.newCall(Request.Builder().url("https://good.example.com/cross").build()).execute().use { resp ->
            assertEquals(302, resp.code)
        }

        // Same-host HTTPS redirect IS followed
        client.newCall(Request.Builder().url("https://good.example.com/same-start").build()).execute().use { resp ->
            assertEquals(200, resp.code)
            assertEquals("ok", resp.body?.string())
        }

        // Response size cap enforces limit
        val oversized = "x".repeat(2048).toResponseBody("text/plain".toMediaType())
        try {
            ServerUrlValidator.readBoundedUtf8(oversized, maxBytes = 1024L)
            fail("Expected IOException when response exceeds cap")
        } catch (_: IOException) {
            // Expected
        }
    }

    @Test
    fun cloudflareColoMapper_mapsIndianCodesAndPassesThroughOthers() {
        assertEquals("Mumbai", CloudflareColoMapper.locationFromCfRay("8f12ab34cd56ef78-BOM"))
        assertEquals("Delhi", CloudflareColoMapper.locationFromCfRay("9911aa22bb33-DEL"))
        assertEquals("Chennai", CloudflareColoMapper.locationFromCfRay("123456-MAA"))
        assertEquals("Bangalore", CloudflareColoMapper.locationFromCfRay("123456-BLR"))
        assertEquals("Hyderabad", CloudflareColoMapper.locationFromCfRay("123456-HYD"))
        assertEquals("Kolkata", CloudflareColoMapper.locationFromCfRay("123456-CCU"))
        assertEquals("SIN", CloudflareColoMapper.locationFromCfRay("abcdef-sin"))
        assertNull(CloudflareColoMapper.locationFromCfRay("nodashatall"))
        assertNull(CloudflareColoMapper.locationFromCfRay("123456-TOOLONGCODE"))
        assertNull(CloudflareColoMapper.locationFromCfRay(null))
    }

    @Test
    fun healthChecker_ranksByMedianPingDropsDeadAndSelectsAutoFastest() = runBlocking {
        val fastCommunity = ServerProfile(
            id = "community_1",
            name = "FastServer",
            location = "Singapore",
            kind = ServerKind.COMMUNITY,
            baseUrl = "https://fast.example.com",
            downloadBaseUrl = "https://fast.example.com/garbage.php",
            uploadBaseUrl = "https://fast.example.com/empty.php",
            pingBaseUrl = "https://fast.example.com/empty.php",
        )
        val slowCommunity = ServerProfile(
            id = "community_2",
            name = "SlowServer",
            location = "Europe",
            kind = ServerKind.COMMUNITY,
            baseUrl = "https://slow.example.com",
            downloadBaseUrl = "https://slow.example.com/garbage.php",
            uploadBaseUrl = "https://slow.example.com/empty.php",
            pingBaseUrl = "https://slow.example.com/empty.php",
        )
        val deadCommunity = ServerProfile(
            id = "community_3",
            name = "DeadServer",
            location = "Offline",
            kind = ServerKind.COMMUNITY,
            baseUrl = "https://dead.example.com",
            downloadBaseUrl = "https://dead.example.com/garbage.php",
            uploadBaseUrl = "https://dead.example.com/empty.php",
            pingBaseUrl = "https://dead.example.com/empty.php",
        )

        val pingSeries = mapOf(
            ServerProfile.CLOUDFLARE_ID to ArrayDeque(listOf(35.0, 22.0, 26.0)), // median 26.0
            fastCommunity.id to ArrayDeque(listOf(18.0, 12.0, 14.0)), // median 14.0
            slowCommunity.id to ArrayDeque(listOf(95.0, 80.0, 88.0)), // median 88.0
        )

        val checker = ServerHealthChecker(
            singlePingOverride = { server ->
                if (server.id == deadCommunity.id) {
                    null to null
                } else {
                    val queue = pingSeries.getValue(server.id)
                    val ms = synchronized(queue) { queue.removeFirst() }
                    val cfLoc = if (server.kind == ServerKind.CLOUDFLARE) "Mumbai" else null
                    ms to cfLoc
                }
            },
        )

        val outcomes = checker.probeCandidatesProgressive(
            candidates = listOf(ServerProfile.CLOUDFLARE, slowCommunity, deadCommunity, fastCommunity),
            maxConcurrency = 8,
            probeCount = 3,
            timeoutMs = 2_000L,
        ).toList()

        assertEquals(4, outcomes.size)
        val ranked = ServerHealthChecker.rankHealthyServers(outcomes)
        assertEquals(3, ranked.size)
        assertEquals(fastCommunity.id, ranked[0].profile.id)
        assertEquals(14.0, ranked[0].medianPingMs!!, 0.01)
        assertEquals(ServerProfile.CLOUDFLARE_ID, ranked[1].profile.id)
        assertEquals("Mumbai", ranked[1].profile.location)
        assertEquals(26.0, ranked[1].medianPingMs!!, 0.01)
        assertEquals(slowCommunity.id, ranked[2].profile.id)

        val autoWinner = ServerHealthChecker.selectAutoFastest(outcomes)
        assertEquals(fastCommunity.id, autoWinner.id)

        // When no server responds, Auto still falls back cleanly to Cloudflare
        val allDead = listOf(
            ServerProbeOutcome(deadCommunity, null, isHealthy = false),
        )
        assertEquals(ServerProfile.CLOUDFLARE_ID, ServerHealthChecker.selectAutoFastest(allDead).id)
    }

    @Test
    fun speedTestEngine_fallsBackToCloudflareWhenNonCloudflareServerStallsWithNoData() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        configureWifiConnectivity(context)

        val stallingCommunity = ServerProfile(
            id = "community_stall",
            name = "StallServer",
            location = "Testville",
            kind = ServerKind.COMMUNITY,
            baseUrl = "https://stall.example.com",
            downloadBaseUrl = "https://stall.example.com/garbage.php",
            uploadBaseUrl = "https://stall.example.com/empty.php",
            pingBaseUrl = "https://stall.example.com/empty.php",
        )

        val mockPayload = ByteArray(64 * 1024) { 0x5A }
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val host = req.url.host
                val path = req.url.encodedPath

                when {
                    // StallServer answers ping fine, but stalls forever on download (returns 0 bytes)
                    host == "stall.example.com" && path.endsWith("garbage.php") -> {
                        val stallingBody = object : okhttp3.ResponseBody() {
                            override fun contentType() = "application/octet-stream".toMediaType()
                            override fun contentLength() = -1L
                            override fun source() = object : InputStream() {
                                override fun read(): Int {
                                    val deadline = System.currentTimeMillis() + 10_000L
                                    while (System.currentTimeMillis() < deadline) {
                                        if (chain.call().isCanceled()) {
                                            throw IOException("Cancelled")
                                        }
                                        try {
                                            Thread.sleep(20L)
                                        } catch (_: InterruptedException) {
                                            throw IOException("Cancelled")
                                        }
                                    }
                                    return -1
                                }
                            }.source().buffer()
                        }
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(stallingBody)
                            .build()
                    }
                    host == "stall.example.com" -> {
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("".toResponseBody("text/plain".toMediaType()))
                            .build()
                    }
                    // Cloudflare succeeds for ping, download, and upload with cf-ray DEL -> Delhi
                    host == "speed.cloudflare.com" && req.method == "POST" -> {
                        req.body?.writeTo(okio.Buffer())
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .header("cf-ray", "77aa88bb99cc-DEL")
                            .body("".toResponseBody("text/plain".toMediaType()))
                            .build()
                    }
                    host == "speed.cloudflare.com" && req.url.queryParameter("bytes") == "0" -> {
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .header("cf-ray", "77aa88bb99cc-DEL")
                            .body("".toResponseBody("text/plain".toMediaType()))
                            .build()
                    }
                    host == "speed.cloudflare.com" -> {
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .header("cf-ray", "77aa88bb99cc-DEL")
                            .body(mockPayload.toResponseBody("application/octet-stream".toMediaType()))
                            .build()
                    }
                    else -> throw IOException("Unexpected host: $host")
                }
            }
            .build()

        val engine = SpeedTestEngine(
            latencyEngine = LatencyEngine(mockClient, singleProbeOverride = null, probeSpacingMs = 0L),
            loadedLatencyEngine = LatencyEngine(mockClient, singleProbeOverride = null, probeSpacingMs = 0L),
            downloadEngine = DownloadEngine(mockClient),
            uploadEngine = UploadEngine(mockClient),
            networkMonitor = NetworkMonitor(context),
        )

        val events = engine.runSpeedTestInternal(
            server = stallingCommunity,
            downloadDurationMs = 450L,
            uploadDurationMs = 450L,
            warmUpMs = 50L,
            noDataFallbackTimeoutMs = 200L,
        ).toList()

        val fallback = events.filterIsInstance<SpeedTestProgress.ServerFallback>().firstOrNull()
        assertNotNull("Expected automatic fallback event to Cloudflare", fallback)
        assertEquals("StallServer", fallback?.previousServerName)
        assertEquals(ServerKind.CLOUDFLARE, fallback?.fallbackServer?.kind)

        val completed = events.filterIsInstance<SpeedTestProgress.Completed>().firstOrNull()
        assertNotNull("Expected speed test to complete on Cloudflare after fallback", completed)
        assertEquals("Cloudflare", completed?.result?.serverName)
        assertEquals("Delhi", completed?.result?.serverLocation)
        assertTrue((completed?.result?.download ?: 0.0) > 0.0)
        assertTrue((completed?.result?.upload ?: 0.0) > 0.0)
        assertFalse(events.any { it is SpeedTestProgress.Failed })
    }

    @Test
    fun speedTestEngine_fallsBackToCloudflareWhenNonCloudflarePingIsUnreachable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        configureWifiConnectivity(context)

        val unreachableCommunity = ServerProfile(
            id = "community_dead",
            name = "DeadCommunity",
            location = "Nowhere",
            kind = ServerKind.COMMUNITY,
            baseUrl = "https://unreachable.example.com",
            downloadBaseUrl = "https://unreachable.example.com/garbage.php",
            uploadBaseUrl = "https://unreachable.example.com/empty.php",
            pingBaseUrl = "https://unreachable.example.com/empty.php",
        )

        val mockPayload = ByteArray(64 * 1024) { 0x33 }
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val host = req.url.host
                if (host == "unreachable.example.com") {
                    throw IOException("Connection refused")
                }
                if (req.method == "POST") {
                    req.body?.writeTo(okio.Buffer())
                }
                val body = if (req.method == "POST" || req.url.queryParameter("bytes") == "0") {
                    "".toResponseBody("text/plain".toMediaType())
                } else {
                    mockPayload.toResponseBody("application/octet-stream".toMediaType())
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("cf-ray", "11223344-BOM")
                    .body(body)
                    .build()
            }
            .build()

        val engine = SpeedTestEngine(
            latencyEngine = LatencyEngine(mockClient, singleProbeOverride = null, probeSpacingMs = 0L),
            loadedLatencyEngine = LatencyEngine(mockClient, singleProbeOverride = null, probeSpacingMs = 0L),
            downloadEngine = DownloadEngine(mockClient),
            uploadEngine = UploadEngine(mockClient),
            networkMonitor = NetworkMonitor(context),
        )

        val events = engine.runSpeedTestInternal(
            server = unreachableCommunity,
            downloadDurationMs = 400L,
            uploadDurationMs = 400L,
            warmUpMs = 50L,
            noDataFallbackTimeoutMs = 200L,
        ).toList()

        val fallback = events.filterIsInstance<SpeedTestProgress.ServerFallback>().firstOrNull()
        assertNotNull(fallback)
        assertEquals("DeadCommunity", fallback?.previousServerName)

        val completed = events.filterIsInstance<SpeedTestProgress.Completed>().firstOrNull()
        assertNotNull(completed)
        assertEquals("Cloudflare", completed?.result?.serverName)
        assertEquals("Mumbai", completed?.result?.serverLocation)
    }

    private fun configureWifiConnectivity(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadowCm = Shadows.shadowOf(cm)
        val caps = ShadowNetworkCapabilities.newInstance()
        Shadows.shadowOf(caps).apply {
            addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        shadowCm.setNetworkCapabilities(cm.activeNetwork, caps)
    }
}
