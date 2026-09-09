package io.github.arttvad9r.hermesbridge

import android.content.Context

interface PairingRecordStore {
    fun deviceId(): String?
    fun saveDeviceId(deviceId: String)
    fun clear()
}

class PairingStore(context: Context) : PairingRecordStore {
    private val preferences = context.getSharedPreferences(
        "hermes_bridge_pairing",
        Context.MODE_PRIVATE,
    )

    override fun deviceId(): String? = preferences.getString(KEY_DEVICE_ID, null)
        ?.takeIf { it.isNotBlank() }

    override fun saveDeviceId(deviceId: String) {
        require(deviceId.startsWith("device_"))
        preferences.edit().putString(KEY_DEVICE_ID, deviceId).apply()
    }

    override fun clear() {
        preferences.edit().remove(KEY_DEVICE_ID).apply()
    }

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
    }
}
