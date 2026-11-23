package com.example.engine.tools

import android.telephony.TelephonyManager
import com.example.data.local.toDomain
import com.example.data.local.toEntity
import com.example.data.models.ConnectionType
import com.example.data.models.TestResult
import com.example.engine.speedtest.NetworkMonitor
import com.example.engine.speedtest.WifiBand
import com.example.engine.speedtest.WifiSignalQuality
import com.example.ui.screens.formatDnsMedianMs
import com.example.viewmodel.rankDnsResults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase3ToolsTest {

    @Test
    fun dnsPacketCodec_buildsValidARecordQueryPacket() {
        val packet = DnsPacketCodec.buildAQueryPacket("google.com", transactionId = 0xABCD)

        // Transaction ID = 0xABCD
        assertEquals(0xAB.toByte(), packet[0])
        assertEquals(0xCD.toByte(), packet[1])
        // Flags = 0x0100 (Standard query, RD=1)
        assertEquals(0x01.toByte(), packet[2])
        assertEquals(0x00.toByte(), packet[3])
        // QDCOUNT = 1
        assertEquals(0x00.toByte(), packet[4])
        assertEquals(0x01.toByte(), packet[5])
        // QNAME: [6]google[3]com[0]
        assertEquals(6.toByte(), packet[12])
        assertEquals('g'.code.toByte(), packet[13])
        assertEquals(3.toByte(), packet[19])
        assertEquals('c'.code.toByte(), packet[20])
        assertEquals(0.toByte(), packet[23])
        // QTYPE = A (1), QCLASS = IN (1)
        assertEquals(0x00.toByte(), packet[24])
        assertEquals(0x01.toByte(), packet[25])
        assertEquals(0x00.toByte(), packet[26])
        assertEquals(0x01.toByte(), packet[27])
        assertEquals(28, packet.size)
    }

    @Test
    fun dnsPacketCodec_validatesResponseHeaderAndTransactionId() {
        val validResponse = ByteArray(12).apply {
            this[0] = 0x12
            this[1] = 0x34
            this[2] = 0x81.toByte() // QR=1, RD=1
            this[3] = 0x80.toByte() // RA=1, RCODE=0 (NOERROR)
        }
        assertTrue(DnsPacketCodec.isValidResponse(validResponse, 12, 0x1234))
        assertFalse(DnsPacketCodec.isValidResponse(validResponse, 12, 0x9999))
        assertFalse(DnsPacketCodec.isValidResponse(validResponse, 10, 0x1234))

        val errorResponse = validResponse.copyOf().apply {
            this[3] = 0x83.toByte() // NXDOMAIN (RCODE=3)
        }
        assertFalse(DnsPacketCodec.isValidResponse(errorResponse, 12, 0x1234))
    }

    @Test
    fun networkMonitor_mapsWifiBandAndSignalQualitySafely() {
        assertEquals(WifiBand.BAND_2_4_GHZ, NetworkMonitor.mapWifiBand(2437))
        assertEquals(WifiBand.BAND_5_GHZ, NetworkMonitor.mapWifiBand(5180))
        assertEquals(WifiBand.BAND_6_GHZ, NetworkMonitor.mapWifiBand(6175))
        assertNull(NetworkMonitor.mapWifiBand(0))
        assertNull(NetworkMonitor.mapWifiBand(-1))

        assertTrue(NetworkMonitor.isValidRssi(-52))
        assertFalse(NetworkMonitor.isValidRssi(-127))
        assertFalse(NetworkMonitor.isValidRssi(0))

        assertEquals(WifiSignalQuality.EXCELLENT, NetworkMonitor.mapWifiSignalQuality(-48))
        assertEquals(WifiSignalQuality.GOOD, NetworkMonitor.mapWifiSignalQuality(-62))
        assertEquals(WifiSignalQuality.FAIR, NetworkMonitor.mapWifiSignalQuality(-72))
        assertEquals(WifiSignalQuality.WEAK, NetworkMonitor.mapWifiSignalQuality(-84))
    }

    @Test
    fun internetInfoClient_parsesIpWhoIsJsonAndChecksIpFormats() {
        val sampleJson = """
            {
              "ip": "203.0.113.42",
              "success": true,
              "connection": {
                "isp": "Example Fiber ISP",
                "org": "Example Corp"
              }
            }
        """.trimIndent()

        val parsed = InternetInfoClient.parseIpWhoIsJson(sampleJson)
        assertEquals("203.0.113.42", parsed.publicIp)
        assertEquals("Example Fiber ISP", parsed.isp)

        val failed = InternetInfoClient.parseIpWhoIsJson("""{"success": false, "message": "rate limited"}""")
        assertNull(failed.publicIp)
        assertNull(failed.isp)

        assertTrue(InternetInfoClient.isLikelyIpv4("198.51.100.7"))
        assertFalse(InternetInfoClient.isLikelyIpv4("2001:db8::1"))
        assertTrue(InternetInfoClient.isLikelyIpv6("2001:db8::1"))
        assertFalse(InternetInfoClient.isLikelyIpv6("198.51.100.7"))
    }

    @Test
    fun dnsRankingAndFormatting_ranksFastestFirstAndNeverShowsZero() {
        assertNull(formatDnsMedianMs(null))
        assertNull(formatDnsMedianMs(0.0))
        assertNull(formatDnsMedianMs(-2.0))
        assertEquals("<0.1 ms", formatDnsMedianMs(0.04))
        assertEquals("14.6 ms", formatDnsMedianMs(14.62))

        val ranked = rankDnsResults(
            listOf(
                DnsResolverResult(DnsResolverId.SYSTEM, null, DnsResolverStatus.COMPLETED, medianMs = 24.0),
                DnsResolverResult(DnsResolverId.CLOUDFLARE, "1.1.1.1", DnsResolverStatus.COMPLETED, medianMs = 11.2),
                DnsResolverResult(DnsResolverId.GOOGLE, "8.8.8.8", DnsResolverStatus.UNREACHABLE, medianMs = null, failedQueries = 15),
                DnsResolverResult(DnsResolverId.QUAD9, "9.9.9.9", DnsResolverStatus.COMPLETED, medianMs = 18.5),
            ),
        )
        assertEquals(
            listOf(
                DnsResolverId.CLOUDFLARE,
                DnsResolverId.QUAD9,
                DnsResolverId.SYSTEM,
                DnsResolverId.GOOGLE,
            ),
            ranked.map { it.id },
        )
    }

    @Test
    fun networkMonitor_mapsCellularGenerationsIncluding5gNsaAnd2g() {
        assertEquals("5G", NetworkMonitor.mapCellularGenerationTag(TelephonyManager.NETWORK_TYPE_NR))
        assertEquals(
            "5G",
            NetworkMonitor.mapCellularGenerationTag(
                networkType = TelephonyManager.NETWORK_TYPE_LTE,
                overrideNetworkType = NetworkMonitor.OVERRIDE_NETWORK_TYPE_NR_NSA,
            ),
        )
        assertEquals(
            "5G",
            NetworkMonitor.mapCellularGenerationTag(
                networkType = TelephonyManager.NETWORK_TYPE_LTE,
                overrideNetworkType = NetworkMonitor.OVERRIDE_NETWORK_TYPE_NR_ADVANCED,
            ),
        )
        assertEquals("4G", NetworkMonitor.mapCellularGenerationTag(TelephonyManager.NETWORK_TYPE_LTE))
        assertEquals("3G", NetworkMonitor.mapCellularGenerationTag(TelephonyManager.NETWORK_TYPE_HSPA))
        assertEquals("3G", NetworkMonitor.mapCellularGenerationTag(TelephonyManager.NETWORK_TYPE_HSPAP))
        assertEquals("3G", NetworkMonitor.mapCellularGenerationTag(TelephonyManager.NETWORK_TYPE_UMTS))
        assertEquals("3G", NetworkMonitor.mapCellularGenerationTag(TelephonyManager.NETWORK_TYPE_EVDO_A))
        assertEquals("2G", NetworkMonitor.mapCellularGenerationTag(TelephonyManager.NETWORK_TYPE_EDGE))
        assertEquals("2G", NetworkMonitor.mapCellularGenerationTag(TelephonyManager.NETWORK_TYPE_GPRS))
        assertNull(NetworkMonitor.mapCellularGenerationTag(TelephonyManager.NETWORK_TYPE_UNKNOWN))
    }

    @Test
    fun testResultEntity_preservesWifiBandAndCellularNetworkTag() {
        val wifiResult = TestResult(
            id = 7L,
            timestamp = 1_700_000_000_000L,
            download = 320.5,
            upload = 145.2,
            ping = 12.4,
            jitter = 1.8,
            connectionType = ConnectionType.WIFI,
            networkName = "HomeFiber",
            wifiBand = "5 GHz",
        )
        val roundTrippedWifi = wifiResult.toEntity().toDomain()
        assertEquals("5 GHz", roundTrippedWifi.wifiBand)
        assertEquals("HomeFiber", roundTrippedWifi.networkName)

        val mobileResult = TestResult(
            id = 8L,
            timestamp = 1_700_000_100_000L,
            download = 210.0,
            upload = 42.5,
            ping = 28.0,
            jitter = 4.2,
            connectionType = ConnectionType.MOBILE,
            networkName = "Jio",
            cellularNetworkTag = "5G",
        )
        val roundTrippedMobile = mobileResult.toEntity().toDomain()
        assertEquals("5G", roundTrippedMobile.cellularNetworkTag)
        assertEquals("Jio", roundTrippedMobile.networkName)
    }
}
