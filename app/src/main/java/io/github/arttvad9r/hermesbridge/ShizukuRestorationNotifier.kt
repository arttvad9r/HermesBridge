package io.github.arttvad9r.hermesbridge

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object ShizukuRestorationNotifier {
    @SuppressLint("MissingPermission")
    fun showIfNeeded(
        context: Context,
        status: ShizukuAccessStatus,
    ): Boolean {
        val appContext = context.applicationContext
        val shouldShow = shouldNotifyShizukuRestoration(
            wasConfigured = AdvancedAccessStore(appContext).wasConfigured(),
            status = status,
            notificationsGranted = BridgeNotificationPermission.isGranted(appContext),
        )
        if (!shouldShow) {
            if (status == ShizukuAccessStatus.READY) cancel(appContext)
            return false
        }

        createChannel(appContext)
        val bridgeIntent = Intent(appContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentIntent = PendingIntent.getActivity(
            appContext,
            20,
            bridgeIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bridge_notification)
            .setContentTitle("Расширенный доступ нужно восстановить")
            .setContentText("Запустите Shizuku после перезагрузки, чтобы вернуть расширенные команды Hermes.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Shizuku был настроен раньше, но сейчас недоступен. Базовое соединение Hermes продолжает работать. Запустите Shizuku, чтобы снова разрешить установку и удаление приложений, force-stop и другие расширенные действия."
                )
            )
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)

        shizukuLaunchIntent(appContext)?.let { launchIntent ->
            val shizukuPendingIntent = PendingIntent.getActivity(
                appContext,
                21,
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, "Открыть Shizuku", shizukuPendingIntent)
        }

        return runCatching {
            NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, builder.build())
            true
        }.getOrDefault(false)
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_ID)
    }

    fun shizukuLaunchIntent(context: Context): Intent? =
        context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE_NAME)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Восстановление расширенного доступа",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Напоминает восстановить Shizuku после перезагрузки, если расширенный доступ был настроен раньше."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private const val CHANNEL_ID = "hermes_bridge_shizuku_restore"
    private const val NOTIFICATION_ID = 1002
}

internal fun shouldNotifyShizukuRestoration(
    wasConfigured: Boolean,
    status: ShizukuAccessStatus,
    notificationsGranted: Boolean,
): Boolean = wasConfigured && notificationsGranted && status != ShizukuAccessStatus.READY

internal const val SHIZUKU_PACKAGE_NAME = "moe.shizuku.privileged.api"
