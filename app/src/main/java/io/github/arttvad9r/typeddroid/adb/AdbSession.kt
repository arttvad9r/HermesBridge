package io.github.arttvad9r.typeddroid.adb

internal enum class AdbSessionState {
    NEW,
    CONNECT_SENT,
    CONNECTED,
    CLOSED,
}

internal sealed interface AdbSessionEvent {
    data class Connected(
        val banner: AdbDeviceBanner,
        val remoteVersion: Int,
        val negotiatedMaxPayload: Int,
    ) : AdbSessionEvent

    data class AuthRequired(val packet: AdbPacket) : AdbSessionEvent
    data class TlsRequested(val packet: AdbPacket) : AdbSessionEvent
    data class Ignored(val packet: AdbPacket) : AdbSessionEvent
}

/**
 * Connection-level ADB state. Logical OPEN/OKAY/WRTE/CLSE channels are delegated to
 * [AdbStreamMultiplexer] after CNXN succeeds.
 */
internal class AdbSession(
    private val hostFeatures: Set<String> = setOf("shell_v2"),
    private val localMaxPayload: Int = AdbProtocol.MAX_PAYLOAD,
) {
    var state: AdbSessionState = AdbSessionState.NEW
        private set

    var deviceBanner: AdbDeviceBanner? = null
        private set

    var negotiatedMaxPayload: Int = localMaxPayload
        private set

    var remoteVersion: Int? = null
        private set

    init {
        require(localMaxPayload in MIN_ADB_MAX_PAYLOAD..AdbProtocol.MAX_PAYLOAD) {
            "Unsupported local ADB max payload."
        }
    }

    fun start(): AdbPacket {
        check(state == AdbSessionState.NEW) { "ADB session has already started." }
        state = AdbSessionState.CONNECT_SENT
        return AdbPacket(
            command = AdbProtocol.CNXN,
            arg0 = AdbProtocol.VERSION,
            arg1 = localMaxPayload,
            payload = buildHostConnectBanner(hostFeatures),
        )
    }

    fun handleHandshake(packet: AdbPacket): AdbSessionEvent {
        check(state != AdbSessionState.CLOSED) { "ADB session is closed." }
        if (state == AdbSessionState.CONNECTED) return AdbSessionEvent.Ignored(packet)

        return when (packet.command) {
            AdbProtocol.CNXN -> {
                require(packet.arg0 >= AdbProtocol.VERSION_MIN) { "Unsupported ADB protocol version." }
                require(packet.arg1 >= MIN_ADB_MAX_PAYLOAD) { "Remote ADB max payload is too small." }
                val banner = parseDeviceConnectBanner(packet.payload)
                deviceBanner = banner
                remoteVersion = packet.arg0
                negotiatedMaxPayload = minOf(localMaxPayload, packet.arg1)
                state = AdbSessionState.CONNECTED
                AdbSessionEvent.Connected(
                    banner = banner,
                    remoteVersion = packet.arg0,
                    negotiatedMaxPayload = negotiatedMaxPayload,
                )
            }

            AdbProtocol.AUTH -> AdbSessionEvent.AuthRequired(packet)
            AdbProtocol.STLS -> {
                require(packet.arg0 >= AdbProtocol.TLS_VERSION) { "Unsupported ADB TLS version." }
                require(packet.arg1 == 0 && packet.payload.isEmpty()) { "Malformed ADB STLS packet." }
                AdbSessionEvent.TlsRequested(packet)
            }
            else -> AdbSessionEvent.Ignored(packet)
        }
    }

    fun newStreamMultiplexer(): AdbStreamMultiplexer {
        check(state == AdbSessionState.CONNECTED) { "ADB session is not connected." }
        return AdbStreamMultiplexer(negotiatedMaxPayload)
    }

    fun close() {
        state = AdbSessionState.CLOSED
    }

    private companion object {
        const val MIN_ADB_MAX_PAYLOAD = 4096
    }
}
