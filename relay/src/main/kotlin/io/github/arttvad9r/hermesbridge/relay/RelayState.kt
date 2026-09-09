package io.github.arttvad9r.hermesbridge.relay

import io.github.arttvad9r.hermesbridge.protocol.BridgeProtocol
import java.security.KeyFactory
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val PAIRING_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

data class PairingCode(
    val code: String,
    val expiresAtEpochMillis: Long,
)

class PairingCodeStore(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    private val codes = ConcurrentHashMap<String, Long>()

    fun create(ttlMillis: Long = 5 * 60_000L): PairingCode {
        require(ttlMillis > 0)
        val raw = buildString(8) {
            repeat(8) {
                append(PAIRING_ALPHABET[random.nextInt(PAIRING_ALPHABET.length)])
            }
        }
        val code = "${raw.take(4)}-${raw.drop(4)}"
        val expiresAt = nowMillis() + ttlMillis
        codes[code] = expiresAt
        return PairingCode(code, expiresAt)
    }

    fun consume(code: String): Boolean {
        val normalized = code.trim().uppercase()
        val expiresAt = codes.remove(normalized) ?: return false
        return expiresAt >= nowMillis()
    }
}

data class DeviceRecord(
    val deviceId: String,
    val publicKey: PublicKey,
    val label: String,
)

class DeviceRegistry {
    private val devices = ConcurrentHashMap<String, DeviceRecord>()

    fun register(publicKey: PublicKey, label: String): DeviceRecord {
        val record = DeviceRecord(
            deviceId = "device_${UUID.randomUUID()}",
            publicKey = publicKey,
            label = label.take(80),
        )
        devices[record.deviceId] = record
        return record
    }

    fun find(deviceId: String): DeviceRecord? = devices[deviceId]
}

object AuthCrypto {
    fun decodeEcPublicKey(base64: String): PublicKey {
        val bytes = Base64.getDecoder().decode(base64)
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
    }

    fun verify(device: DeviceRecord, challenge: String, signatureBase64: String): Boolean {
        val signatureBytes = runCatching { Base64.getDecoder().decode(signatureBase64) }
            .getOrElse { return false }
        return Signature.getInstance("SHA256withECDSA").run {
            initVerify(device.publicKey)
            update(BridgeProtocol.authSigningBytes(device.deviceId, challenge))
            verify(signatureBytes)
        }
    }

    fun newChallenge(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
