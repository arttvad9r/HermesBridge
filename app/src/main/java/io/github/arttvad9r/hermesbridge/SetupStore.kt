package io.github.arttvad9r.hermesbridge

import android.content.Context

class SetupStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "hermes_bridge_setup",
        Context.MODE_PRIVATE,
    )

    fun isCompleted(): Boolean = preferences.getBoolean(KEY_COMPLETED, false)

    fun markCompleted() {
        preferences.edit().putBoolean(KEY_COMPLETED, true).apply()
    }

    fun reset() {
        preferences.edit().remove(KEY_COMPLETED).apply()
    }

    private companion object {
        const val KEY_COMPLETED = "completed"
    }
}
