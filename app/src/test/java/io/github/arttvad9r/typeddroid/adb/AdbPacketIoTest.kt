package io.github.arttvad9r.typeddroid.adb

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AdbPacketIoTest {
    @Test
    fun readerHandlesFragmentedInput() {
        val expected = AdbPacket(
            command = AdbProtocol.WRTE,
            arg0 = 11,
            arg1 = 22,
            payload = "fragmented".encodeToByteArray(),
        )
        val encoded = AdbPacketCodec.encode(expected)
        val reader = AdbPacketReader(FragmentedInputStream(encoded, maxChunk = 2), checksumRequired = true)

        val actual = reader.read()

        assertEquals(expected.command, actual.command)
        assertEquals(expected.arg0, actual.arg0)
        assertEquals(expected.arg1, actual.arg1)
        assertArrayEquals(expected.payload, actual.payload)
    }

    @Test
    fun readerRejectsOversizedPayloadBeforeReadingBody() {
        val header = ByteBuffer.allocate(AdbProtocol.HEADER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(AdbProtocol.WRTE)
            .putInt(1)
            .putInt(2)
            .putInt(AdbProtocol.MAX_PAYLOAD + 1)
            .putInt(0)
            .putInt(AdbProtocol.WRTE xor -1)
            .array()

        assertThrows(IllegalArgumentException::class.java) {
            AdbPacketReader(ByteArrayInputStream(header), checksumRequired = false).read()
        }
    }

    @Test
    fun readerRejectsTruncatedPayload() {
        val encoded = AdbPacketCodec.encode(
            AdbPacket(AdbProtocol.WRTE, 1, 2, byteArrayOf(1, 2, 3, 4)),
        ).copyOf(AdbProtocol.HEADER_SIZE + 2)

        assertThrows(EOFException::class.java) {
            AdbPacketReader(ByteArrayInputStream(encoded), checksumRequired = true).read()
        }
    }

    @Test
    fun writerFlushesCompleteFrame() {
        val output = TrackingOutputStream()
        val packet = AdbPacket(AdbProtocol.OKAY, 7, 8)

        AdbPacketWriter(output, includeChecksum = true).write(packet)

        assertEquals(1, output.flushCount)
        assertArrayEquals(AdbPacketCodec.encode(packet), output.toByteArray())
    }

    private class FragmentedInputStream(
        private val bytes: ByteArray,
        private val maxChunk: Int,
    ) : InputStream() {
        private var offset = 0

        override fun read(): Int =
            if (offset >= bytes.size) -1 else bytes[offset++].toInt() and 0xff

        override fun read(target: ByteArray, off: Int, len: Int): Int {
            if (offset >= bytes.size) return -1
            val count = minOf(len, maxChunk, bytes.size - offset)
            bytes.copyInto(target, off, offset, offset + count)
            offset += count
            return count
        }
    }

    private class TrackingOutputStream : ByteArrayOutputStream() {
        var flushCount = 0
            private set

        override fun flush() {
            flushCount += 1
            super.flush()
        }
    }
}
