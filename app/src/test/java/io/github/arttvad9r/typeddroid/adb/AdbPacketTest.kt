package io.github.arttvad9r.typeddroid.adb

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AdbPacketTest {
    @Test
    fun commandConstantsMatchAdbWireEncoding() {
        assertEquals(0x4e584e43, AdbProtocol.CNXN)
        assertEquals(0x48545541, AdbProtocol.AUTH)
        assertEquals(0x4e45504f, AdbProtocol.OPEN)
        assertEquals(0x59414b4f, AdbProtocol.OKAY)
        assertEquals(0x45545257, AdbProtocol.WRTE)
        assertEquals(0x45534c43, AdbProtocol.CLSE)
        assertEquals(0x534c5453, AdbProtocol.STLS)
        assertEquals("CNXN", AdbProtocol.commandName(AdbProtocol.CNXN))
    }

    @Test
    fun packetRoundTripPreservesHeaderAndPayload() {
        val payload = "host::features=shell_v2;".encodeToByteArray()
        val source = AdbPacket(
            command = AdbProtocol.CNXN,
            arg0 = AdbProtocol.VERSION,
            arg1 = AdbProtocol.MAX_PAYLOAD,
            payload = payload,
        )

        val encoded = AdbPacketCodec.encode(source)
        val decoded = AdbPacketCodec.decode(encoded)

        assertEquals(source.command, decoded.command)
        assertEquals(source.arg0, decoded.arg0)
        assertEquals(source.arg1, decoded.arg1)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun headerIsLittleEndianAndUsesCommandComplementMagic() {
        val packet = AdbPacket(AdbProtocol.OPEN, arg0 = 7, arg1 = 0, payload = byteArrayOf(1, 2, 3))
        val encoded = AdbPacketCodec.encode(packet)
        val header = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(AdbProtocol.OPEN, header.int)
        assertEquals(7, header.int)
        assertEquals(0, header.int)
        assertEquals(3, header.int)
        assertEquals(6, header.int)
        assertEquals(AdbProtocol.OPEN xor -1, header.int)
    }

    @Test
    fun skipChecksumVersionCanDecodeZeroChecksumFrames() {
        val frame = AdbPacketCodec.encode(
            AdbPacket(AdbProtocol.WRTE, 1, 2, byteArrayOf(0x10, 0x20)),
            includeChecksum = false,
        )

        val decoded = AdbPacketCodec.decode(frame, checksumRequired = false)
        assertArrayEquals(byteArrayOf(0x10, 0x20), decoded.payload)
    }

    @Test
    fun malformedMagicLengthAndChecksumAreRejected() {
        val valid = AdbPacketCodec.encode(
            AdbPacket(AdbProtocol.WRTE, 1, 2, byteArrayOf(1, 2, 3)),
        )

        val badMagic = valid.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) {
            AdbPacketCodec.decode(badMagic)
        }

        val truncated = valid.copyOf(valid.size - 1)
        assertThrows(IllegalArgumentException::class.java) {
            AdbPacketCodec.decode(truncated)
        }

        val badChecksum = valid.copyOf().also { it[16] = (it[16].toInt() xor 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) {
            AdbPacketCodec.decode(badChecksum)
        }
    }

    @Test
    fun payloadLimitIsEnforcedBeforeAllocationOnDecode() {
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
            AdbPacketCodec.decode(header, checksumRequired = false)
        }
    }
}
