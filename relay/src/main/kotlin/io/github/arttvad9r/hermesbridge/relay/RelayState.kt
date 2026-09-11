package io.github.arttvad9r.hermesbridge.relay

import io.github.arttvad9r.hermesbridge.protocol.BridgeProtocol
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.KeyFactory
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

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

@Serializable
private data class StoredDevice(
    val deviceId: String,
    val publicKey: String,
    val label: String,
)

@Serializable
private data class StoredDeviceRegistry(
    val version: Int = 1,
    val devices: List<StoredDevice> = emptyList(),
)

class DeviceRegistry(
    private val storagePath: Path? = null,
) {
    private val devices = ConcurrentHashMap<String, DeviceRecord>()
    private val persistenceLock = Any()

    init {
        loadFromDisk()
    }

    fun register(publicKey: PublicKey, label: String): DeviceRecord {
        val record = DeviceRecord(
            deviceId = "device_${UUID.randomUUID()}",
            publicKey = publicKey,
            label = label.take(80),
        )
        synchronized(persistenceLock) {
            devices[record.deviceId] = record
            try {
                persistLocked()
            } catch (error: Throwable) {
                // Trust is not established until the durable registry reflects it. Keep memory and
                // disk consistent so a transient storage failure cannot create a process-local
                // device that disappears or changes meaning after relay restart.
                devices.remove(record.deviceId, record)
                throw error
            }
        }
        return record
    }

    fun find(deviceId: String): DeviceRecord? = devices[deviceId]

    fun list(): List<DeviceRecord> = devices.values.sortedBy { it.label.lowercase() }

    fun revoke(deviceId: String): Boolean = synchronized(persistenceLock) {
        val removed = devices.remove(deviceId) ?: return@synchronized false
        try {
            persistLocked()
        } catch (error: Throwable) {
            // A revocation that was not durably persisted must not be reported as effective in
            // memory. Restore the exact trusted record and let the caller surface the failure.
            devices[deviceId] = removed
            throw error
        }
        true
    }

    private fun loadFromDisk() {
        val path = storagePath ?: return
        if (!Files.exists(path)) return

        val raw = Files.readString(path)
        if (raw.isBlank()) return
        val stored = BridgeProtocol.json.decodeFromString<StoredDeviceRegistry>(raw)
        require(stored.version == 1) { "Unsupported relay device registry version: ${stored.version}" }

        stored.devices.forEach { item ->
            val publicKey = AuthCrypto.decodeEcPublicKey(item.publicKey)
            devices[item.deviceId] = DeviceRecord(
                deviceId = item.deviceId,
                publicKey = publicKey,
                label = item.label.take(80),
            )
        }
    }

    private fun persistLocked() {
        val path = storagePath ?: return
        val absolute = path.toAbsolutePath()
        val parent = requireNotNull(absolute.parent)
        Files.createDirectories(parent)

        val stored = StoredDeviceRegistry(
            devices = devices.values
                .sortedBy { it.deviceId }
                .map { record ->
                    StoredDevice(
                        deviceId = record.deviceId,
                        publicKey = Base64.getEncoder().encodeToString(record.publicKey.encoded),
                        label = record.label,
                    )
                },
        )
        val content = BridgeProtocol.json.encodeToString(stored)
        val temp = Files.createTempFile(parent, ".devices-", ".json.tmp")
        try {
            Files.writeString(temp, content)
            try {
                Files.move(
                    temp,
                    absolute,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }
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
