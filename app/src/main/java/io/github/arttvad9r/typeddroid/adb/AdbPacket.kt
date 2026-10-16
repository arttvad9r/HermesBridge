package io.github.arttvad9r.typeddroid.adb

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal repository-owned implementation of the ADB transport frame format.
 *
 * The wire header is six little-endian uint32 values followed by data_length bytes:
 * command, arg0, arg1, data_length, data_check, magic.
 */
internal object AdbProtocol {
    const val VERSION_MIN = 0x01000000
    const val VERSION_SKIP_CHECKSUM = 0x01000001
    const val VERSION = VERSION_SKIP_CHECKSUM
    const val TLS_VERSION = 0x01000000

    const val HEADER_SIZE = 24
    const val MAX_PAYLOAD = 1024 * 1024

    val SYNC = command("SYNC")
    val CNXN = command("CNXN")
    val OPEN = command("OPEN")
    val OKAY = command("OKAY")
    val CLSE = command("CLSE")
    val WRTE = command("WRTE")
    val AUTH = command("AUTH")
    val STLS = command("STLS")

    fun command(ascii: String): Int {
        require(ascii.length == 4 && ascii.all { it.code in 0x20..0x7e }) {
            "ADB command must be exactly four printable ASCII characters."
        }
        return ascii[0].code or
            (ascii[1].code shl 8) or
            (ascii[2].code shl 16) or
            (ascii[3].code shl 24)
    }

    fun commandName(command: Int): String = buildString(4) {
        repeat(4) { index ->
            append(((command ushr (index * 8)) and 0xff).toChar())
        }
    }
}

internal data class AdbPacket(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val payload: ByteArray = ByteArray(0),
) {
    init {
        require(payload.size <= AdbProtocol.MAX_PAYLOAD) {
            "ADB payload exceeds ${AdbProtocol.MAX_PAYLOAD} bytes."
        }
    }

    val checksum: Int
        get() = adbPayloadChecksum(payload)

    val magic: Int
        get() = command xor -1
}

internal object AdbPacketCodec {
    fun encode(packet: AdbPacket, includeChecksum: Boolean = true): ByteArray {
        val result = ByteBuffer
            .allocate(AdbProtocol.HEADER_SIZE + packet.payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)

        result.putInt(packet.command)
        result.putInt(packet.arg0)
        result.putInt(packet.arg1)
        result.putInt(packet.payload.size)
        result.putInt(if (includeChecksum) packet.checksum else 0)
        result.putInt(packet.magic)
        result.put(packet.payload)
        return result.array()
    }

    fun decode(frame: ByteArray, checksumRequired: Boolean = true): AdbPacket {
        require(frame.size >= AdbProtocol.HEADER_SIZE) { "Truncated ADB packet header." }
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)

        val command = buffer.int
        val arg0 = buffer.int
        val arg1 = buffer.int
        val dataLength = buffer.int
        val dataCheck = buffer.int
        val magic = buffer.int

        require(magic == (command xor -1)) { "Invalid ADB packet magic." }
        require(dataLength in 0..AdbProtocol.MAX_PAYLOAD) { "Invalid ADB payload length." }
        require(frame.size == AdbProtocol.HEADER_SIZE + dataLength) {
            "ADB frame length does not match its header."
        }

        val payload = ByteArray(dataLength)
        buffer.get(payload)
        if (checksumRequired) {
            require(dataCheck == adbPayloadChecksum(payload)) { "Invalid ADB payload checksum." }
        }

        return AdbPacket(
            command = command,
            arg0 = arg0,
            arg1 = arg1,
            payload = payload,
        )
    }
}

internal fun adbPayloadChecksum(payload: ByteArray): Int {
    var checksum = 0
    payload.forEach { byte -> checksum += byte.toInt() and 0xff }
    return checksum
}
