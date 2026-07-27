package jp.kotoba.kekkai.tunnel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PacketPlaneTest {
    @Test fun decisionsFailClosed() {
        assertEquals(100, KotobaPacketPlane.decide(4, 60, 1280, 1, 1))
        assertEquals(12, KotobaPacketPlane.decide(4, 60, 1280, 1, 0))
        assertEquals(13, KotobaPacketPlane.decide(4, 60, 1280, 0, 1))
        assertEquals(11, KotobaPacketPlane.decide(6, 1400, 1280, 1, 1))
        assertEquals(0, KotobaPacketPlane.decide(7, 60, 1280, 1, 1))
    }

    @Test fun malformedPacketsAreRejected() {
        assertNull(PacketParser.parse(byteArrayOf(), 0))
        assertNull(PacketParser.parse(byteArrayOf(0x45), 1))
        assertNull(PacketParser.parse(ByteArray(40).also { it[0] = 0x70 }, 40))
    }
}
