package com.example.engine.tools

import java.io.ByteArrayOutputStream

/**
 * Minimal RFC 1035 DNS A-record query encoder and response header validator (no external library).
 */
object DnsPacketCodec {
    private const val HEADER_SIZE_BYTES = 12
    private const val TYPE_A = 1
    private const val CLASS_IN = 1

    /**
     * Builds a standard recursive DNS A-record query packet (RD=1, QDCOUNT=1) for [domain].
     */
    fun buildAQueryPacket(domain: String, transactionId: Int): ByteArray {
        val normalized = domain.trim().trimEnd('.')
        require(normalized.isNotEmpty()) { "Domain must not be blank" }

        val out = ByteArrayOutputStream(64)

        // Header: 12 bytes
        out.write((transactionId ushr 8) and 0xFF)
        out.write(transactionId and 0xFF)
        // Flags: standard query with Recursion Desired (RD = 1) -> 0x0100
        out.write(0x01)
        out.write(0x00)
        // QDCOUNT = 1
        out.write(0x00)
        out.write(0x01)
        // ANCOUNT = 0, NSCOUNT = 0, ARCOUNT = 0
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)

        // Question: QNAME labels
        for (label in normalized.split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            require(bytes.isNotEmpty() && bytes.size <= 63) {
                "Invalid DNS label '$label' in '$domain'"
            }
            out.write(bytes.size)
            out.write(bytes)
        }
        // Root label terminator
        out.write(0x00)

        // QTYPE = A (1)
        out.write((TYPE_A ushr 8) and 0xFF)
        out.write(TYPE_A and 0xFF)

        // QCLASS = IN (1)
        out.write((CLASS_IN ushr 8) and 0xFF)
        out.write(CLASS_IN and 0xFF)

        return out.toByteArray()
    }

    /**
     * Validates that [response] is a DNS response matching [expectedTransactionId] with RCODE == NOERROR (0).
     */
    fun isValidResponse(
        response: ByteArray,
        length: Int,
        expectedTransactionId: Int,
    ): Boolean {
        if (length < HEADER_SIZE_BYTES || response.size < HEADER_SIZE_BYTES) return false

        val responseId = ((response[0].toInt() and 0xFF) shl 8) or (response[1].toInt() and 0xFF)
        if (responseId != (expectedTransactionId and 0xFFFF)) return false

        val flagsByte1 = response[2].toInt() and 0xFF
        val flagsByte2 = response[3].toInt() and 0xFF
        val isResponse = (flagsByte1 and 0x80) != 0
        val rcode = flagsByte2 and 0x0F

        return isResponse && rcode == 0
    }
}
