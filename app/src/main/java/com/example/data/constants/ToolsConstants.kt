package com.example.data.constants

/**
 * Centralized, swappable endpoints and tuning constants for Phase 3 Tools
 * (Network info external IP/ISP/IPv4/IPv6 checks and DNS benchmark).
 */
object ToolsConstants {
    // External internet lookup endpoints (swappable)
    const val IP_INFO_ENDPOINT = "https://ipwho.is/"
    const val IPV4_CHECK_ENDPOINT = "https://api.ipify.org"
    const val IPV6_CHECK_ENDPOINT = "https://api6.ipify.org"
    const val INTERNET_LOOKUP_TIMEOUT_SECONDS = 4L

    // Public DNS resolver IPv4 addresses (UDP port 53)
    const val DNS_UDP_PORT = 53
    const val DNS_CLOUDFLARE_IP = "1.1.1.1"
    const val DNS_GOOGLE_IP = "8.8.8.8"
    const val DNS_QUAD9_IP = "9.9.9.9"

    // DNS benchmark parameters: 5 popular domains, 3 rounds each, 2-second timeout
    val DNS_BENCHMARK_DOMAINS: List<String> = listOf(
        "google.com",
        "cloudflare.com",
        "wikipedia.org",
        "github.com",
        "amazon.com",
    )
    const val DNS_BENCHMARK_ROUNDS = 3
    const val DNS_QUERY_TIMEOUT_MS = 2_000
}
