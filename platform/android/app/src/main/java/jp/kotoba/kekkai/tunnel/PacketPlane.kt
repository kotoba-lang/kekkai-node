package jp.kotoba.kekkai.tunnel

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ParsedPacket(val family: Int, val bytes: ByteArray)

object PacketParser {
    fun parse(input: ByteArray, count: Int): ParsedPacket? {
        if (count <= 0 || count > input.size) return null
        val version = (input[0].toInt() ushr 4) and 0x0f
        return when (version) {
            4 -> {
                if (count < 20) return null
                val headerLength = (input[0].toInt() and 0x0f) * 4
                val total = ByteBuffer.wrap(input, 2, 2)
                    .order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff
                if (headerLength < 20 || total < headerLength || total > count) null
                else ParsedPacket(4, input.copyOf(total))
            }
            6 -> {
                if (count < 40) return null
                val payload = ByteBuffer.wrap(input, 4, 2)
                    .order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff
                val total = 40 + payload
                if (total > count) null
                else ParsedPacket(6, input.copyOf(total))
            }
            else -> null
        }
    }
}

/**
 * Build-gated host adapter for kotoba/kekkai/packet_plane.kotoba.
 * Android preBuild compiles the authoritative Kotoba source for Android and
 * JavaScript, then compares every boundary vector with the executable
 * JavaScript artifact before this adapter can be packaged.
 */
object KotobaPacketPlane {
    const val OVERLAY = 100
    const val BYPASS = 200

    fun decide(
        family: Int,
        packetLength: Int,
        mtu: Int,
        routeAction: Int,
        transportReady: Int
    ): Int {
        if (family != 4 && family != 6) return 0
        val minimum = if (family == 4) 20 else 40
        if (packetLength < minimum || packetLength > 65_575) return 10
        if (mtu < 1280 || mtu > 9000 || packetLength > mtu) return 11
        if (routeAction == 1 && transportReady == 0) return 12
        if (routeAction == 1) return OVERLAY
        if (routeAction == 2) return BYPASS
        return 13
    }
}
