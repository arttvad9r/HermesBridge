package io.github.arttvad9r.typeddroid.adb

import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AdbPairingIoTest {
    @Test
    fun `packet io reads header before bounded payload`() {
        val expected = AdbPairingPacket(
            AdbPairingPacketType.SPAKE2_MSG,
            ByteArray(32) { it.toByte() },
        )
        val transport = BufferTransport(AdbPairingPacketCodec.encode(expected))

        assertEquals(expected, AdbPairingPacketIo(transport).read())
        assertEquals(listOf(6, 32), transport.readSizes)
    }

    @Test
    fun `packet io rejects oversized length before reading payload`() {
        val header = byteArrayOf(
            1,
            0,
            0,
            0,
            0x40,
            0x01,
        )
        val transport = BufferTransport(header)

        assertThrows(IllegalArgumentException::class.java) {
            AdbPairingPacketIo(transport).read()
        }
        assertEquals(listOf(6), transport.readSizes)
    }

    private class BufferTransport(initial: ByteArray) : AdbPairingTransport {
        private val input = ArrayDeque<Byte>().apply { initial.forEach(::addLast) }
        val readSizes = mutableListOf<Int>()
        override val exportedKeyMaterial = ByteArray(64)

        override fun readFully(size: Int): ByteArray {
            readSizes += size
            return ByteArray(size) { input.removeFirst() }
        }

        override fun writeFully(data: ByteArray) = Unit
        override fun close() = Unit
    }
}
