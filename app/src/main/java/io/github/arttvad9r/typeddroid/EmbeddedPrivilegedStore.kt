package io.github.arttvad9r.typeddroid

import android.content.Context

internal data class EmbeddedPrivilegedEndpoint(
    val port: Int,
    val authToken: String,
) {
    init {
        require(port in 1..65535) { "Invalid embedded privileged port." }
        require(isValidEmbeddedPrivilegedAuthToken(authToken)) { "Invalid embedded privileged auth token." }
    }
}

internal class EmbeddedPrivilegedStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "typed_droid_embedded_privileged",
        Context.MODE_PRIVATE,
    )

    fun endpoint(): EmbeddedPrivilegedEndpoint? {
        val port = preferences.getInt(KEY_PORT, -1)
        val token = preferences.getString(KEY_AUTH_TOKEN, null) ?: return null
        return runCatching { EmbeddedPrivilegedEndpoint(port, token) }.getOrNull()
    }

    fun saveEndpoint(endpoint: EmbeddedPrivilegedEndpoint) {
        check(
            preferences.edit()
                .putInt(KEY_PORT, endpoint.port)
                .putString(KEY_AUTH_TOKEN, endpoint.authToken)
                .remove(LEGACY_SOCKET_NAME)
                .commit()
        ) { "Could not persist embedded privileged helper state." }
    }

    fun wasAdbPaired(): Boolean = preferences.getBoolean(KEY_ADB_PAIRED, false)

    fun markAdbPaired() {
        check(preferences.edit().putBoolean(KEY_ADB_PAIRED, true).commit()) {
            "Could not persist embedded ADB pairing state."
        }
    }

    fun clear() {
        check(
            preferences.edit()
                .remove(KEY_PORT)
                .remove(KEY_AUTH_TOKEN)
                .remove(LEGACY_SOCKET_NAME)
                .commit()
        ) { "Could not clear embedded privileged helper state." }
    }

    fun clearAll() {
        check(preferences.edit().clear().commit()) {
            "Could not clear embedded privileged state."
        }
    }

    private companion object {
        const val KEY_PORT = "loopback_port_v2"
        const val KEY_AUTH_TOKEN = "loopback_auth_token_v2"
        const val KEY_ADB_PAIRED = "adb_paired_v1"
        const val LEGACY_SOCKET_NAME = "socket_name_v1"
    }
}

internal fun isValidEmbeddedPrivilegedAuthToken(value: String): Boolean =
    EMBEDDED_PRIVILEGED_AUTH_TOKEN.matches(value)

private val EMBEDDED_PRIVILEGED_AUTH_TOKEN = Regex("^[a-f0-9]{64}$")
