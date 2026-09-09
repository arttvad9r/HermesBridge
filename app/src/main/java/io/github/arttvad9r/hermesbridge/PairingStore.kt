package io.github.arttvad9r.hermesbridge

import android.content.Context

class PairingStore(context: Context) {
    private val preferences = context.getSharedPreferences(
        "hermes_bridge_pairing",
        Context.MODE_PRIVATE,
    )

    fun deviceId(): String? = preferences.getString(KEY_DEVICE_ID, null)
        ?.takeIf { it.isNotBlank() }

    fun saveDeviceId(deviceId: String) {
        require(deviceId.startsWith("device_"))
        preferences.edit().putString(KEY_DEVICE_ID, deviceId).apply()
    }

    fun clear() {
        preferences.edit().remove(KEY_DEVICE_ID).apply()
    }

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
    }
}
