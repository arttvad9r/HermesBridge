package io.github.arttvad9r.typeddroid

import android.content.Context
import io.github.arttvad9r.typeddroid.security.AgentTrustMode

enum class PairingRecordPhase {
    PENDING,
    ACTIVE,
}

data class PairingRecordSnapshot(
    val deviceId: String,
    val phase: PairingRecordPhase,
    val trustMode: AgentTrustMode = AgentTrustMode.SAFE,
)

interface PairingRecordStore {
    fun deviceId(): String?
    fun saveDeviceId(deviceId: String)

    /**
     * Durably records the relay-assigned identity before proof-of-possession is sent.
     * Test/in-memory stores may treat this as an active save; the Android store keeps
     * a distinct PENDING phase so process death can recover the in-flight pairing.
     */
    fun stageDeviceId(deviceId: String) {
        saveDeviceId(deviceId)
    }

    /** Promotes a staged pairing after AUTH_OK. */
    fun confirmDeviceId(deviceId: String) {
        saveDeviceId(deviceId)
    }

    fun hasPendingDeviceId(): Boolean = false
    fun trustMode(): AgentTrustMode = AgentTrustMode.SAFE
    fun saveTrustMode(mode: AgentTrustMode) {
        require(deviceId() != null) { "Cannot set agent trust before pairing." }
    }
    fun clear()
}

class PairingStore(context: Context) : PairingRecordStore {
    private val preferences = context.getSharedPreferences(
        "typed_droid_pairing",
        Context.MODE_PRIVATE,
    )

    override fun deviceId(): String? = readRecord()?.deviceId

    override fun hasPendingDeviceId(): Boolean =
        readRecord()?.phase == PairingRecordPhase.PENDING

    override fun trustMode(): AgentTrustMode =
        readRecord()?.trustMode ?: AgentTrustMode.SAFE

    override fun saveTrustMode(mode: AgentTrustMode) {
        val current = readRecord()
            ?: error("Cannot set agent trust before pairing.")
        persistRecord(current.copy(trustMode = mode))
    }

    override fun saveDeviceId(deviceId: String) {
        val trustMode = readRecord()?.trustMode ?: AgentTrustMode.SAFE
        persistRecord(PairingRecordSnapshot(deviceId, PairingRecordPhase.ACTIVE, trustMode))
    }

    override fun stageDeviceId(deviceId: String) {
        persistRecord(PairingRecordSnapshot(deviceId, PairingRecordPhase.PENDING, AgentTrustMode.SAFE))
    }

    override fun confirmDeviceId(deviceId: String) {
        requireValidPairingDeviceId(deviceId)
        val current = readRecord()
            ?: error("Cannot confirm a pairing that is not stored locally.")
        require(current.deviceId == deviceId) {
            "Cannot confirm a different pairing identity."
        }
        if (current.phase == PairingRecordPhase.ACTIVE) return
        persistRecord(current.copy(phase = PairingRecordPhase.ACTIVE))
    }

    override fun clear() {
        check(
            preferences.edit()
                .remove(KEY_PAIRING_RECORD)
                .remove(KEY_LEGACY_DEVICE_ID)
                .commit()
        ) { "Could not durably clear bridge pairing state." }
    }

    private fun readRecord(): PairingRecordSnapshot? {
        // Presence of the versioned key is authoritative. If it is malformed, fail closed instead
        // of falling back to a potentially stale legacy device_id value.
        if (preferences.contains(KEY_PAIRING_RECORD)) {
            val raw = preferences.all[KEY_PAIRING_RECORD] as? String ?: return null
            return decodePairingRecord(raw)
        }

        val legacy = preferences.all[KEY_LEGACY_DEVICE_ID] as? String ?: return null
        return legacy
            .takeIf(::isValidPairingDeviceId)
            ?.let { PairingRecordSnapshot(it, PairingRecordPhase.ACTIVE) }
    }

    private fun persistRecord(record: PairingRecordSnapshot) {
        val encoded = encodePairingRecord(record)
        check(
            preferences.edit()
                .putString(KEY_PAIRING_RECORD, encoded)
                .remove(KEY_LEGACY_DEVICE_ID)
                .commit()
        ) { "Could not durably persist bridge pairing state." }
    }

    private companion object {
        const val KEY_PAIRING_RECORD = "pairing_record_v1"
        const val KEY_LEGACY_DEVICE_ID = "device_id"
    }
}

internal fun encodePairingRecord(record: PairingRecordSnapshot): String {
    requireValidPairingDeviceId(record.deviceId)
    val phase = when (record.phase) {
        PairingRecordPhase.PENDING -> "pending"
        PairingRecordPhase.ACTIVE -> "active"
    }
    val trustMode = when (record.trustMode) {
        AgentTrustMode.SAFE -> "safe"
        AgentTrustMode.TRUSTED -> "trusted"
    }
    return "v2|$phase|$trustMode|${record.deviceId}"
}

internal fun decodePairingRecord(raw: String): PairingRecordSnapshot? {
    val parts = raw.split('|')
    return when {
        parts.size == 3 && parts[0] == "v1" -> decodeV1PairingRecord(parts)
        parts.size == 4 && parts[0] == "v2" -> decodeV2PairingRecord(parts)
        else -> null
    }
}

private fun decodeV1PairingRecord(parts: List<String>): PairingRecordSnapshot? {
    val phase = decodePairingPhase(parts[1]) ?: return null
    val deviceId = parts[2]
    if (!isValidPairingDeviceId(deviceId)) return null
    return PairingRecordSnapshot(
        deviceId = deviceId,
        phase = phase,
        trustMode = AgentTrustMode.SAFE,
    )
}

private fun decodeV2PairingRecord(parts: List<String>): PairingRecordSnapshot? {
    val phase = decodePairingPhase(parts[1]) ?: return null
    val trustMode = when (parts[2]) {
        "safe" -> AgentTrustMode.SAFE
        "trusted" -> AgentTrustMode.TRUSTED
        else -> return null
    }
    val deviceId = parts[3]
    if (!isValidPairingDeviceId(deviceId)) return null
    return PairingRecordSnapshot(deviceId, phase, trustMode)
}

private fun decodePairingPhase(value: String): PairingRecordPhase? = when (value) {
    "pending" -> PairingRecordPhase.PENDING
    "active" -> PairingRecordPhase.ACTIVE
    else -> null
}

internal fun isValidPairingDeviceId(deviceId: String): Boolean =
    PAIRING_DEVICE_ID_REGEX.matches(deviceId)

private fun requireValidPairingDeviceId(deviceId: String) {
    require(isValidPairingDeviceId(deviceId)) { "Invalid bridge device ID." }
}

private val PAIRING_DEVICE_ID_REGEX = Regex("^device_[A-Za-z0-9._-]{1,120}$")
