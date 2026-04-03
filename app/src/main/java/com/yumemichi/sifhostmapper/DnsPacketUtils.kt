package com.yumemichi.sifhostmapper

import java.io.ByteArrayOutputStream

object DnsPacketUtils {
    data class Query(
        val name: String,
        val qType: Int,
        val qClass: Int,
        val questionEndOffset: Int
    )

    fun parseQuery(dns: ByteArray): Query? {
        if (dns.size < 12) return null
        val qdCount = readU16(dns, 4)
        if (qdCount < 1) return null

        var index = 12
        val labels = mutableListOf<String>()

        while (index < dns.size) {
            val len = dns[index].toInt() and 0xFF
            if (len == 0) {
                index += 1
                break
            }
            if (len and 0xC0 != 0) return null
            if (index + 1 + len > dns.size) return null
            val label = String(dns, index + 1, len, Charsets.US_ASCII)
            labels += label
            index += 1 + len
        }

        if (index + 4 > dns.size) return null

        val qType = readU16(dns, index)
        val qClass = readU16(dns, index + 2)
        val qName = labels.joinToString(".").lowercase()

        return Query(
            name = qName,
            qType = qType,
            qClass = qClass,
            questionEndOffset = index + 4
        )
    }

    fun buildMappedAResponse(queryDns: ByteArray, mappedIpBytes: ByteArray, ttlSeconds: Int = 60): ByteArray? {
        val query = parseQuery(queryDns) ?: return null
        if (query.qType != 1 || query.qClass != 1) return null
        if (mappedIpBytes.size != 4) return null

        val out = ByteArrayOutputStream()

        out.write(queryDns.copyOfRange(0, 2))
        out.write(byteArrayOf(0x81.toByte(), 0x80.toByte()))
        out.write(byteArrayOf(0x00, 0x01))
        out.write(byteArrayOf(0x00, 0x01))
        out.write(byteArrayOf(0x00, 0x00, 0x00, 0x00))

        out.write(queryDns.copyOfRange(12, query.questionEndOffset))

        out.write(byteArrayOf(0xC0.toByte(), 0x0C))
        out.write(byteArrayOf(0x00, 0x01))
        out.write(byteArrayOf(0x00, 0x01))
        out.write(
            byteArrayOf(
                ((ttlSeconds ushr 24) and 0xFF).toByte(),
                ((ttlSeconds ushr 16) and 0xFF).toByte(),
                ((ttlSeconds ushr 8) and 0xFF).toByte(),
                (ttlSeconds and 0xFF).toByte()
            )
        )
        out.write(byteArrayOf(0x00, 0x04))
        out.write(mappedIpBytes)

        return out.toByteArray()
    }

    fun buildNoErrorEmptyResponse(queryDns: ByteArray): ByteArray? {
        val query = parseQuery(queryDns) ?: return null
        val out = ByteArrayOutputStream()

        out.write(queryDns.copyOfRange(0, 2))
        out.write(byteArrayOf(0x81.toByte(), 0x80.toByte()))
        out.write(byteArrayOf(0x00, 0x01))
        out.write(byteArrayOf(0x00, 0x00))
        out.write(byteArrayOf(0x00, 0x00, 0x00, 0x00))

        out.write(queryDns.copyOfRange(12, query.questionEndOffset))
        return out.toByteArray()
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    }
}
