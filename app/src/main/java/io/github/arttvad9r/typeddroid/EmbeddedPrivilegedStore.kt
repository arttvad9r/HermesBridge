package io.github.arttvad9r.typeddroid

import android.content.Context

internal class EmbeddedPrivilegedStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "typed_droid_embedded_privileged",
        Context.MODE_PRIVATE,
    )

    fun socketName(): String? = preferences.getString(KEY_SOCKET_NAME, null)
        ?.takeIf(::isValidEmbeddedPrivilegedSocketName)

    fun saveSocketName(socketName: String) {
        require(isValidEmbeddedPrivilegedSocketName(socketName)) { "Invalid embedded privileged socket name." }
        check(preferences.edit().putString(KEY_SOCKET_NAME, socketName).commit()) {
            "Could not persist embedded privileged helper state."
        }
    }

    fun wasAdbPaired(): Boolean = preferences.getBoolean(KEY_ADB_PAIRED, false)

    fun markAdbPaired() {
        check(preferences.edit().putBoolean(KEY_ADB_PAIRED, true).commit()) {
            "Could not persist embedded ADB pairing state."
        }
    }

    fun clear() {
        check(preferences.edit().remove(KEY_SOCKET_NAME).commit()) {
            "Could not clear embedded privileged helper state."
        }
    }

    fun clearAll() {
        check(preferences.edit().clear().commit()) {
            "Could not clear embedded privileged state."
        }
    }

    private companion object {
        const val KEY_SOCKET_NAME = "socket_name_v1"
        const val KEY_ADB_PAIRED = "adb_paired_v1"
    }
}

internal fun isValidEmbeddedPrivilegedSocketName(value: String): Boolean =
    EMBEDDED_PRIVILEGED_SOCKET_NAME.matches(value)

private val EMBEDDED_PRIVILEGED_SOCKET_NAME = Regex("^typeddroid-[a-f0-9]{32}$")
