package io.github.arttvad9r.typeddroid.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbPairingWireTest {
    @Test
    fun `pairing packet uses version type and network-order length`() {
        val packet = AdbPairingPacket(AdbPairingPacketType.SPAKE2_MSG, byteArrayOf(1, 2, 3, 4))
        val wire = AdbPairingPacketCodec.encode(packet)

        assertEquals(1, wire[0].toInt())
        assertEquals(0, wire[1].toInt())
        assertEquals(0, wire[2].toInt())
        assertEquals(0, wire[3].toInt())
        assertEquals(0, wire[4].toInt())
        assertEquals(4, wire[5].toInt())
        assertEquals(packet, AdbPairingPacketCodec.decode(wire))
    }

    @Test
    fun `pairing packet rejects unsupported version and zero payload`() {
        val good = AdbPairingPacketCodec.encode(
            AdbPairingPacket(AdbPairingPacketType.SPAKE2_MSG, byteArrayOf(1))
        )
        good[0] = 2
        assertThrows(IllegalArgumentException::class.java) { AdbPairingPacketCodec.decode(good) }
        assertThrows(IllegalArgumentException::class.java) {
            AdbPairingPacket(AdbPairingPacketType.PEER_INFO, byteArrayOf())
        }
    }

    @Test
    fun `peer info is fixed size null padded rsa record`() {
        val value = "abc== typed@test"
        val peerInfo = AdbPairingPeerInfo.rsaPublicKey(value)
        assertEquals(8192, peerInfo.size)
        assertEquals(0, peerInfo[0].toInt())
        assertEquals(value, peerInfo.copyOfRange(1, 1 + value.length).decodeToString())
        assertTrue(peerInfo.copyOfRange(1 + value.length, peerInfo.size).all { it == 0.toByte() })
    }
}
