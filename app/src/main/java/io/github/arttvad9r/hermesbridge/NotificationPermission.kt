package io.github.arttvad9r.hermesbridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object BridgeNotificationPermission {
    fun isGranted(context: Context): Boolean {
        if (!requiresRuntimePermission(Build.VERSION.SDK_INT)) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }
}

internal fun requiresRuntimePermission(sdkInt: Int): Boolean =
    sdkInt >= Build.VERSION_CODES.TIRAMISU
