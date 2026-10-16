package io.github.arttvad9r.typeddroid.adb

internal data class AdbPairingResult(
    val deviceGuid: String,
)

internal class AdbPairingClient(
    private val spakeFactory: (ByteArray) -> AdbSpake2 = { password ->
        AdbSpake2(AdbSpake2.Role.CLIENT, password)
    },
) {
    fun pair(
        pairingCode: String,
        transport: AdbPairingTransport,
        adbPublicKey: String,
    ): AdbPairingResult {
        require(PAIRING_CODE.matches(pairingCode)) { "ADB pairing code must contain exactly six digits." }
        require(transport.exportedKeyMaterial.size == TLS_EXPORTER_BYTES) {
            "ADB pairing TLS exporter must contain exactly $TLS_EXPORTER_BYTES bytes."
        }

        val password = pairingCode.encodeToByteArray() + transport.exportedKeyMaterial
        try {
            val spake = spakeFactory(password)
            val io = AdbPairingPacketIo(transport)
            io.write(
                AdbPairingPacket(
                    type = AdbPairingPacketType.SPAKE2_MSG,
                    payload = spake.message,
                )
            )

            val peerSpakePacket = io.read()
            require(peerSpakePacket.type == AdbPairingPacketType.SPAKE2_MSG) {
                "ADB pairing peer sent PeerInfo before SPAKE2 completed."
            }
            require(peerSpakePacket.payload.size == SPAKE2_MESSAGE_BYTES) {
                "ADB pairing peer sent an invalid SPAKE2 message size."
            }

            val keyMaterial = spake.deriveKey(peerSpakePacket.payload)
            val cipher = try {
                AdbPairingCipher(keyMaterial)
            } finally {
                keyMaterial.fill(0)
            }

            val ownPeerInfo = AdbPairingPeerInfo.rsaPublicKey(adbPublicKey)
            val encryptedOwnPeerInfo = cipher.encrypt(ownPeerInfo)
            ownPeerInfo.fill(0)
            io.write(
                AdbPairingPacket(
                    type = AdbPairingPacketType.PEER_INFO,
                    payload = encryptedOwnPeerInfo,
                )
            )

            val peerInfoPacket = io.read()
            require(peerInfoPacket.type == AdbPairingPacketType.PEER_INFO) {
                "ADB pairing peer sent an unexpected packet after SPAKE2."
            }
            val peerInfo = cipher.decrypt(peerInfoPacket.payload)
            return try {
                AdbPairingResult(
                    deviceGuid = AdbPairingPeerInfo.parseDeviceGuid(peerInfo),
                )
            } finally {
                peerInfo.fill(0)
            }
        } finally {
            password.fill(0)
        }
    }

    fun pair(
        host: String,
        port: Int,
        pairingCode: String,
        identity: AndroidAdbIdentity,
        tlsClient: AdbPairingTlsClient = AdbPairingTlsClient(),
    ): AdbPairingResult = tlsClient.connect(host, port, identity).use { channel ->
        pair(
            pairingCode = pairingCode,
            transport = channel,
            adbPublicKey = identity.adbPublicKey,
        )
    }

    private companion object {
        val PAIRING_CODE = Regex("^[0-9]{6}$")
        const val TLS_EXPORTER_BYTES = 64
        const val SPAKE2_MESSAGE_BYTES = 32
    }
}
