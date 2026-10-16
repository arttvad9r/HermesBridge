package io.github.arttvad9r.typeddroid.adb

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal enum class AdbPairingPacketType(val wireValue: Int) {
    SPAKE2_MSG(0),
    PEER_INFO(1),
    ;

    companion object {
        fun fromWireValue(value: Int): AdbPairingPacketType = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("Unknown ADB pairing packet type: $value")
    }
}

internal data class AdbPairingPacket(
    val type: AdbPairingPacketType,
    val payload: ByteArray,
) {
    init {
        require(payload.isNotEmpty() && payload.size <= ADB_PAIRING_MAX_PAYLOAD) {
            "ADB pairing payload is outside the supported size."
        }
    }

    override fun equals(other: Any?): Boolean =
        other is AdbPairingPacket && type == other.type && payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * type.hashCode() + payload.contentHashCode()
}

internal object AdbPairingPacketCodec {
    const val HEADER_SIZE = 6
    const val VERSION = 1

    fun encode(packet: AdbPairingPacket): ByteArray {
        val result = ByteBuffer
            .allocate(HEADER_SIZE + packet.payload.size)
            .order(ByteOrder.BIG_ENDIAN)
        result.put(VERSION.toByte())
        result.put(packet.type.wireValue.toByte())
        result.putInt(packet.payload.size)
        result.put(packet.payload)
        return result.array()
    }

    fun decode(frame: ByteArray): AdbPairingPacket {
        require(frame.size >= HEADER_SIZE) { "Truncated ADB pairing packet header." }
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        val version = buffer.get().toInt() and 0xff
        require(version == VERSION) { "Unsupported ADB pairing packet version." }
        val type = AdbPairingPacketType.fromWireValue(buffer.get().toInt() and 0xff)
        val length = buffer.int
        require(length in 1..ADB_PAIRING_MAX_PAYLOAD) { "Invalid ADB pairing payload length." }
        require(frame.size == HEADER_SIZE + length) { "ADB pairing frame length mismatch." }
        val payload = ByteArray(length)
        buffer.get(payload)
        return AdbPairingPacket(type, payload)
    }
}

/** Fixed 8192-byte PeerInfo structure used after SPAKE2 succeeds. */
internal object AdbPairingPeerInfo {
    const val SIZE = 8192
    const val TYPE_ADB_RSA_PUBLIC_KEY = 0
    const val TYPE_ADB_DEVICE_GUID = 1

    fun rsaPublicKey(adbPublicKey: String): ByteArray = build(TYPE_ADB_RSA_PUBLIC_KEY, adbPublicKey)

    fun deviceGuid(deviceGuid: String): ByteArray = build(TYPE_ADB_DEVICE_GUID, deviceGuid)

    fun parseDeviceGuid(peerInfo: ByteArray): String {
        require(peerInfo.size == SIZE) { "ADB PeerInfo must be exactly $SIZE bytes." }
        require(peerInfo[0].toInt() and 0xff == TYPE_ADB_DEVICE_GUID) {
            "ADB pairing server returned an unexpected PeerInfo type."
        }
        val data = peerInfo.copyOfRange(1, peerInfo.size)
        val terminator = data.indexOf(0.toByte())
        require(terminator in 1..MAX_DEVICE_GUID_BYTES) { "ADB device GUID is missing or invalid." }
        require(data.copyOfRange(terminator + 1, data.size).all { it == 0.toByte() }) {
            "ADB device GUID PeerInfo contains unexpected trailing data."
        }
        val value = data.copyOfRange(0, terminator).decodeToString(throwOnInvalidSequence = true)
        require(value.length <= MAX_DEVICE_GUID_BYTES && value.all(::isSafeGuidChar)) {
            "ADB device GUID contains invalid characters."
        }
        return value
    }

    private fun build(type: Int, value: String): ByteArray {
        val bytes = value.encodeToByteArray()
        require(bytes.isNotEmpty() && bytes.size <= SIZE - 2) { "ADB PeerInfo value does not fit." }
        require(bytes.none { it == 0.toByte() }) { "ADB PeerInfo value contains a NUL byte." }
        val peerInfo = ByteArray(SIZE)
        peerInfo[0] = type.toByte()
        bytes.copyInto(peerInfo, destinationOffset = 1)
        return peerInfo
    }

    private fun isSafeGuidChar(char: Char): Boolean =
        char.isLetterOrDigit() || char == '-' || char == '_' || char == '.' || char == ':'

    private const val MAX_DEVICE_GUID_BYTES = 512
}

internal const val ADB_PAIRING_MAX_PAYLOAD = AdbPairingPeerInfo.SIZE * 2
