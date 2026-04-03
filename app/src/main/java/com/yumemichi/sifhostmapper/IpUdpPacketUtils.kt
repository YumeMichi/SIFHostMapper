package com.yumemichi.sifhostmapper

object IpUdpPacketUtils {
    data class UdpDnsQueryPacket(
        val srcIp: Int,
        val dstIp: Int,
        val srcPort: Int,
        val dstPort: Int,
        val dnsPayload: ByteArray
    )

    fun parseUdpDnsPacket(packet: ByteArray, length: Int): UdpDnsQueryPacket? {
        if (length < 28) return null
        val version = (packet[0].toInt() ushr 4) and 0x0F
        if (version != 4) return null

        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < 20 || length < ihl + 8) return null

        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 17) return null

        val totalLen = readU16(packet, 2).coerceAtMost(length)
        if (totalLen < ihl + 8) return null

        val srcPort = readU16(packet, ihl)
        val dstPort = readU16(packet, ihl + 2)
        val udpLen = readU16(packet, ihl + 4)
        if (udpLen < 8) return null

        val payloadOffset = ihl + 8
        val payloadEnd = (payloadOffset + udpLen - 8).coerceAtMost(totalLen)
        if (payloadEnd < payloadOffset) return null

        val dns = packet.copyOfRange(payloadOffset, payloadEnd)

        return UdpDnsQueryPacket(
            srcIp = readU32(packet, 12),
            dstIp = readU32(packet, 16),
            srcPort = srcPort,
            dstPort = dstPort,
            dnsPayload = dns
        )
    }

    fun buildIpv4UdpPacket(
        srcIp: Int,
        dstIp: Int,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val ipHeaderLen = 20
        val udpHeaderLen = 8
        val totalLen = ipHeaderLen + udpHeaderLen + payload.size
        val packet = ByteArray(totalLen)

        packet[0] = 0x45
        packet[1] = 0x00
        writeU16(packet, 2, totalLen)
        writeU16(packet, 4, 0)
        writeU16(packet, 6, 0)
        packet[8] = 64
        packet[9] = 17
        writeU16(packet, 10, 0)
        writeU32(packet, 12, srcIp)
        writeU32(packet, 16, dstIp)

        writeU16(packet, ipHeaderLen, srcPort)
        writeU16(packet, ipHeaderLen + 2, dstPort)
        writeU16(packet, ipHeaderLen + 4, udpHeaderLen + payload.size)
        writeU16(packet, ipHeaderLen + 6, 0)

        payload.copyInto(packet, ipHeaderLen + udpHeaderLen)

        val ipChecksum = ipv4HeaderChecksum(packet, 0, ipHeaderLen)
        writeU16(packet, 10, ipChecksum)

        // IPv4 UDP checksum may be set to 0 (disabled). Using 0 avoids
        // compatibility issues where custom checksum logic causes drops.
        writeU16(packet, ipHeaderLen + 6, 0)

        return packet
    }

    private fun ipv4HeaderChecksum(bytes: ByteArray, offset: Int, len: Int): Int {
        var sum = 0L
        var i = offset
        while (i < offset + len) {
            if (i == offset + 10) {
                i += 2
                continue
            }
            sum += readU16(bytes, i).toLong()
            i += 2
        }
        while (sum ushr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    }

    private fun readU32(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 1] = (value and 0xFF).toByte()
    }

    private fun writeU32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = ((value ushr 24) and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 3] = (value and 0xFF).toByte()
    }
}
