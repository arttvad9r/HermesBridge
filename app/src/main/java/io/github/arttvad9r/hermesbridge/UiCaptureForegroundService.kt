package io.github.arttvad9r.hermesbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Owns one short-lived, explicitly consented MediaProjection at a time.
 *
 * This service intentionally creates no VirtualDisplay and exposes no pixels. It establishes the
 * Android 14/15-compliant consent/foreground-service lifecycle that later screenshot work can use
 * without making capture persistent or remotely startable.
 */
class UiCaptureForegroundService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var tearingDown = false

    private val expiryRunnable = Runnable {
        finishSession("Screen-capture session expired after five minutes.")
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSession(intent)
            ACTION_STOP -> finishSession("Screen-capture session stopped locally.")
            else -> stopWithoutSession("Screen-capture service received no valid action.")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        val statusBeforeDestroy = UiCaptureSessionRuntime.state.value.status
        clearProjection(null)
        if (
            statusBeforeDestroy == UiCaptureSessionStatus.ACTIVE ||
            statusBeforeDestroy == UiCaptureSessionStatus.STARTING
        ) {
            UiCaptureSessionRuntime.stopped("Screen-capture session stopped with its foreground service.")
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startSession(intent: Intent) {
        // A single service instance owns at most one projection. A future capture UI must stop the
        // active session before asking Android for another consent grant.
        if (projection != null) return

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
        val consentData = consentIntentExtra(intent)
        if (resultCode == Int.MIN_VALUE || consentData == null) {
            stopWithoutSession("Android screen-capture consent data was missing.")
            return
        }

        // Android 14+ requires the mediaProjection foreground service to be running before the
        // MediaProjection token is obtained. Do this before calling getMediaProjection().
        startAsForeground()
        UiCaptureSessionRuntime.starting()

        val manager = getSystemService(MediaProjectionManager::class.java)
        val resolvedProjection = try {
            manager.getMediaProjection(resultCode, consentData)
        } catch (error: Throwable) {
            UiCaptureSessionRuntime.error(
                "Could not start Android screen capture: ${error.message ?: error::class.java.simpleName}"
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        if (resolvedProjection == null) {
            UiCaptureSessionRuntime.error("Android did not return a MediaProjection session.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                finishSession("Android revoked or stopped the screen-capture session.")
            }
        }
        projection = resolvedProjection
        projectionCallback = callback
        resolvedProjection.registerCallback(callback, mainHandler)

        val now = System.currentTimeMillis()
        UiCaptureSessionRuntime.active(now)
        mainHandler.removeCallbacks(expiryRunnable)
        mainHandler.postDelayed(expiryRunnable, UI_CAPTURE_SESSION_MAX_DURATION_MILLIS)
    }

    private fun finishSession(message: String) {
        clearProjection(message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopWithoutSession(message: String) {
        UiCaptureSessionRuntime.error(message)
        mainHandler.removeCallbacks(expiryRunnable)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun clearProjection(message: String?) {
        if (tearingDown) return
        tearingDown = true
        try {
            mainHandler.removeCallbacks(expiryRunnable)
            val currentProjection = projection
            val currentCallback = projectionCallback
            projection = null
            projectionCallback = null
            if (currentProjection != null && currentCallback != null) {
                runCatching { currentProjection.unregisterCallback(currentCallback) }
            }
            if (currentProjection != null) {
                runCatching { currentProjection.stop() }
            }
            if (message != null) {
                UiCaptureSessionRuntime.stopped(message)
            }
        } finally {
            tearingDown = false
        }
    }

    private fun startAsForeground() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, UiCaptureForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bridge_notification)
            .setContentTitle("Hermes Bridge · захват экрана")
            .setContentText("Активна временная сессия захвата. Hermes ещё не получает изображение.")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Остановить захват", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Захват экрана Hermes",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Показывает активную временную сессию MediaProjection"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    @Suppress("DEPRECATION")
    private fun consentIntentExtra(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_CONSENT_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_CONSENT_DATA)
        }

    companion object {
        private const val CHANNEL_ID = "hermes_bridge_ui_capture"
        private const val NOTIFICATION_ID = 1003
        private const val ACTION_START = "io.github.arttvad9r.hermesbridge.UI_CAPTURE_START"
        private const val ACTION_STOP = "io.github.arttvad9r.hermesbridge.UI_CAPTURE_STOP"
        private const val EXTRA_RESULT_CODE = "media_projection_result_code"
        private const val EXTRA_CONSENT_DATA = "media_projection_consent_data"

        fun start(context: Context, resultCode: Int, consentData: Intent) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, UiCaptureForegroundService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_CONSENT_DATA, consentData),
            )
        }
    }
}
