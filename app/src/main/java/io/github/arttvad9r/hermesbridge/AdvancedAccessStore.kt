package io.github.arttvad9r.hermesbridge

import android.content.Context

class AdvancedAccessStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    fun wasConfigured(): Boolean = preferences.getBoolean(KEY_WAS_CONFIGURED, false)

    fun markConfigured() {
        preferences.edit().putBoolean(KEY_WAS_CONFIGURED, true).apply()
    }

    companion object {
        private const val PREFS_NAME = "hermes_bridge_advanced_access"
        private const val KEY_WAS_CONFIGURED = "shizuku_was_configured"
    }
}
