package io.github.arttvad9r.typeddroid.adb

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal interface AdbPairingTransport : AutoCloseable {
    val exportedKeyMaterial: ByteArray
    fun readFully(size: Int): ByteArray
    fun writeFully(data: ByteArray)
}

internal class AdbPairingPacketIo(
    private val transport: AdbPairingTransport,
) {
    fun write(packet: AdbPairingPacket) {
        transport.writeFully(AdbPairingPacketCodec.encode(packet))
    }

    fun read(): AdbPairingPacket {
        val header = transport.readFully(AdbPairingPacketCodec.HEADER_SIZE)
        require(header.size == AdbPairingPacketCodec.HEADER_SIZE) {
            "ADB pairing transport returned a truncated header."
        }
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val version = buffer.get().toInt() and 0xff
        require(version == AdbPairingPacketCodec.VERSION) {
            "Unsupported ADB pairing packet version."
        }
        AdbPairingPacketType.fromWireValue(buffer.get().toInt() and 0xff)
        val payloadLength = buffer.int
        require(payloadLength in 1..ADB_PAIRING_MAX_PAYLOAD) {
            "Invalid ADB pairing payload length."
        }
        val payload = transport.readFully(payloadLength)
        require(payload.size == payloadLength) { "ADB pairing transport returned a truncated payload." }
        return AdbPairingPacketCodec.decode(header + payload)
    }
}
