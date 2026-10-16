package io.github.arttvad9r.typeddroid.adb

/**
 * Repository-owned host-side state machine for logical ADB streams.
 *
 * ADB stream IDs are sender-relative. For packets received from adbd, arg0 is the
 * device's local stream ID and arg1 is our local stream ID. Each WRTE is flow-controlled
 * by an OKAY before another WRTE may be sent on the same stream.
 */
internal class AdbStreamMultiplexer(
    private val maxPayload: Int = AdbProtocol.MAX_PAYLOAD,
) {
    private val streams = linkedMapOf<Int, MutableStream>()
    private var nextLocalId = 1

    init {
        require(maxPayload in 1..AdbProtocol.MAX_PAYLOAD) { "Invalid negotiated ADB payload size." }
    }

    fun open(service: String): AdbOpenRequest {
        val payload = encodeServiceDestination(service, maxPayload)
        val localId = allocateLocalId()
        streams[localId] = MutableStream(
            localId = localId,
            remoteId = null,
            service = service,
            awaitingWriteAck = false,
        )
        return AdbOpenRequest(
            localId = localId,
            packet = AdbPacket(
                command = AdbProtocol.OPEN,
                arg0 = localId,
                arg1 = 0,
                payload = payload,
            ),
        )
    }

    fun write(localId: Int, payload: ByteArray): AdbPacket {
        require(payload.isNotEmpty()) { "ADB WRTE payload must not be empty." }
        require(payload.size <= maxPayload) { "ADB WRTE payload exceeds negotiated max payload." }
        val stream = requireOpenStream(localId)
        check(!stream.awaitingWriteAck) { "ADB stream is waiting for an OKAY acknowledgement." }
        stream.awaitingWriteAck = true
        return AdbPacket(
            command = AdbProtocol.WRTE,
            arg0 = stream.localId,
            arg1 = checkNotNull(stream.remoteId),
            payload = payload.copyOf(),
        )
    }

    fun close(localId: Int): AdbPacket? {
        val stream = streams.remove(localId) ?: return null
        val remoteId = stream.remoteId ?: return null
        return AdbPacket(
            command = AdbProtocol.CLSE,
            arg0 = stream.localId,
            arg1 = remoteId,
        )
    }

    fun snapshot(localId: Int): AdbStreamSnapshot? = streams[localId]?.let { stream ->
        AdbStreamSnapshot(
            localId = stream.localId,
            remoteId = stream.remoteId,
            service = stream.service,
            awaitingWriteAck = stream.awaitingWriteAck,
        )
    }

    fun handle(packet: AdbPacket): AdbStreamDispatch = when (packet.command) {
        AdbProtocol.OKAY -> handleOkay(packet)
        AdbProtocol.WRTE -> handleWrite(packet)
        AdbProtocol.CLSE -> handleClose(packet)
        else -> throw AdbStreamProtocolException(
            "${AdbProtocol.commandName(packet.command)} is not a logical-stream packet."
        )
    }

    private fun handleOkay(packet: AdbPacket): AdbStreamDispatch {
        requireEmptyControlPayload(packet)
        require(packet.arg0 != 0 && packet.arg1 != 0) { "ADB OKAY stream IDs must be non-zero." }
        val stream = streams[packet.arg1]
            ?: throw AdbStreamProtocolException("ADB OKAY references an unknown local stream.")

        val existingRemoteId = stream.remoteId
        return if (existingRemoteId == null) {
            stream.remoteId = packet.arg0
            AdbStreamDispatch(
                events = listOf(
                    AdbStreamEvent.Opened(
                        localId = stream.localId,
                        remoteId = packet.arg0,
                        service = stream.service,
                    )
                )
            )
        } else {
            requireRemoteId(stream, packet.arg0)
            check(stream.awaitingWriteAck) { "Unexpected ADB OKAY without an outstanding WRTE." }
            stream.awaitingWriteAck = false
            AdbStreamDispatch(events = listOf(AdbStreamEvent.WriteAcknowledged(stream.localId)))
        }
    }

    private fun handleWrite(packet: AdbPacket): AdbStreamDispatch {
        require(packet.arg0 != 0 && packet.arg1 != 0) { "ADB WRTE stream IDs must be non-zero." }
        val stream = requireOpenStream(packet.arg1)
        requireRemoteId(stream, packet.arg0)
        require(packet.payload.size <= maxPayload) { "ADB WRTE payload exceeds negotiated max payload." }

        return AdbStreamDispatch(
            outgoing = listOf(
                AdbPacket(
                    command = AdbProtocol.OKAY,
                    arg0 = stream.localId,
                    arg1 = checkNotNull(stream.remoteId),
                )
            ),
            events = listOf(
                AdbStreamEvent.Data(
                    localId = stream.localId,
                    payload = packet.payload.copyOf(),
                )
            ),
        )
    }

    private fun handleClose(packet: AdbPacket): AdbStreamDispatch {
        requireEmptyControlPayload(packet)
        require(packet.arg1 != 0) { "ADB CLSE remote stream ID must be non-zero." }
        val stream = streams[packet.arg1] ?: return AdbStreamDispatch()
        if (stream.remoteId != null && packet.arg0 != 0) {
            requireRemoteId(stream, packet.arg0)
        }
        streams.remove(stream.localId)
        return AdbStreamDispatch(
            events = listOf(
                AdbStreamEvent.Closed(
                    localId = stream.localId,
                    remoteId = packet.arg0.takeIf { it != 0 } ?: stream.remoteId,
                    failedToOpen = stream.remoteId == null && packet.arg0 == 0,
                )
            )
        )
    }

    private fun requireOpenStream(localId: Int): MutableStream {
        val stream = streams[localId]
            ?: throw AdbStreamProtocolException("Unknown ADB local stream ID.")
        check(stream.remoteId != null) { "ADB stream has not finished opening." }
        return stream
    }

    private fun requireRemoteId(stream: MutableStream, receivedRemoteId: Int) {
        if (stream.remoteId != receivedRemoteId) {
            throw AdbStreamProtocolException("ADB packet references the wrong remote stream ID.")
        }
    }

    private fun requireEmptyControlPayload(packet: AdbPacket) {
        require(packet.payload.isEmpty()) {
            "${AdbProtocol.commandName(packet.command)} must not contain a payload."
        }
    }

    private fun allocateLocalId(): Int {
        repeat(Int.MAX_VALUE - 1) {
            val candidate = nextLocalId
            nextLocalId = if (nextLocalId == Int.MAX_VALUE) 1 else nextLocalId + 1
            if (candidate != 0 && candidate !in streams) return candidate
        }
        error("No ADB local stream IDs are available.")
    }

    private data class MutableStream(
        val localId: Int,
        var remoteId: Int?,
        val service: String,
        var awaitingWriteAck: Boolean,
    )
}

internal data class AdbOpenRequest(
    val localId: Int,
    val packet: AdbPacket,
)

internal data class AdbStreamSnapshot(
    val localId: Int,
    val remoteId: Int?,
    val service: String,
    val awaitingWriteAck: Boolean,
)

internal data class AdbStreamDispatch(
    val outgoing: List<AdbPacket> = emptyList(),
    val events: List<AdbStreamEvent> = emptyList(),
)

internal sealed interface AdbStreamEvent {
    data class Opened(
        val localId: Int,
        val remoteId: Int,
        val service: String,
    ) : AdbStreamEvent

    data class WriteAcknowledged(val localId: Int) : AdbStreamEvent

    data class Data(
        val localId: Int,
        val payload: ByteArray,
    ) : AdbStreamEvent {
        override fun equals(other: Any?): Boolean =
            other is Data && localId == other.localId && payload.contentEquals(other.payload)

        override fun hashCode(): Int = 31 * localId + payload.contentHashCode()
    }

    data class Closed(
        val localId: Int,
        val remoteId: Int?,
        val failedToOpen: Boolean,
    ) : AdbStreamEvent
}

internal class AdbStreamProtocolException(message: String) : IllegalStateException(message)

private fun encodeServiceDestination(service: String, maxPayload: Int): ByteArray {
    require(service.isNotEmpty()) { "ADB service destination is empty." }
    require('\u0000' !in service) { "ADB service destination contains NUL." }
    val encoded = service.encodeToByteArray() + byteArrayOf(0)
    require(encoded.size <= maxPayload) { "ADB service destination exceeds negotiated max payload." }
    return encoded
}
