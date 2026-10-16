package io.github.arttvad9r.typeddroid.adb

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class AdbPacketReader(
    private val input: InputStream,
    private val checksumRequired: Boolean,
) {
    fun read(): AdbPacket {
        val header = readExactly(AdbProtocol.HEADER_SIZE)
        val headerBuffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        headerBuffer.int // command; validated by codec after payload is read
        headerBuffer.int // arg0
        headerBuffer.int // arg1
        val dataLength = headerBuffer.int

        require(dataLength in 0..AdbProtocol.MAX_PAYLOAD) {
            "Invalid ADB payload length."
        }

        val payload = readExactly(dataLength)
        val frame = ByteArray(header.size + payload.size)
        header.copyInto(frame, destinationOffset = 0)
        payload.copyInto(frame, destinationOffset = header.size)
        return AdbPacketCodec.decode(frame, checksumRequired = checksumRequired)
    }

    private fun readExactly(size: Int): ByteArray {
        if (size == 0) return ByteArray(0)
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = input.read(result, offset, size - offset)
            if (count < 0) {
                throw EOFException("ADB stream ended after $offset of $size expected bytes.")
            }
            if (count == 0) {
                val single = input.read()
                if (single < 0) {
                    throw EOFException("ADB stream ended after $offset of $size expected bytes.")
                }
                result[offset++] = single.toByte()
            } else {
                offset += count
            }
        }
        return result
    }
}

internal class AdbPacketWriter(
    private val output: OutputStream,
    private val includeChecksum: Boolean,
) {
    fun write(packet: AdbPacket) {
        output.write(AdbPacketCodec.encode(packet, includeChecksum = includeChecksum))
        output.flush()
    }
}
