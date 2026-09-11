package io.github.arttvad9r.hermesbridge

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

internal const val BRIDGE_NOTIFICATION_CHANNEL_ID = "hermes_bridge_connection"

object BridgeNotificationPermission {
    fun isGranted(context: Context): Boolean {
        if (!requiresRuntimePermission(Build.VERSION.SDK_INT)) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isBridgeStatusVisible(context: Context): Boolean {
        if (!isGranted(context)) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true

        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(BRIDGE_NOTIFICATION_CHANNEL_ID)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }
}

internal fun requiresRuntimePermission(sdkInt: Int): Boolean =
    sdkInt >= Build.VERSION_CODES.TIRAMISU
