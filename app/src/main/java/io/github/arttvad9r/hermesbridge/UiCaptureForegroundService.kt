package io.github.arttvad9r.hermesbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Owns one short-lived, explicitly consented MediaProjection at a time.
 *
 * Each consent is used for exactly one non-secure VirtualDisplay. Hermes Bridge acquires one
 * bounded RGBA frame, immediately erases its process-local byte copy, releases all capture
 * resources, and stops the session. No pixels are persisted or exposed to relay/MCP state.
 */
class UiCaptureForegroundService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var sessionDeadline: UiCaptureSessionDeadline? = null
    private var frameHandled = false
    private var tearingDown = false

    private val expiryRunnable = object : Runnable {
        override fun run() {
            val deadline = sessionDeadline ?: return
            val nextDelayMillis = uiCaptureSessionWatchdogDelayMillis(
                deadline = deadline,
                nowElapsedRealtimeMillis = SystemClock.elapsedRealtime(),
            )
            if (nextDelayMillis == null) {
                finishSession("Screen-capture session expired after five minutes.")
            } else {
                mainHandler.postDelayed(this, nextDelayMillis)
            }
        }
    }

    private val frameTimeoutRunnable = Runnable {
        if (!frameHandled && projection != null) {
            failSession("Android did not provide a screen frame within the capture timeout.")
        }
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
        // One Android 14+ consent grant may back only one createVirtualDisplay invocation. Refuse a
        // second start until the current projection has been completely torn down.
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
            failSession("Could not start Android screen capture: ${error.message ?: error::class.java.simpleName}")
            return
        }
        if (resolvedProjection == null) {
            failSession("Android did not return a MediaProjection session.")
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

        val dimensions = try {
            val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            boundedUiCaptureDimensions(bounds.width(), bounds.height())
        } catch (error: Throwable) {
            failSession("Could not determine safe screen-capture dimensions.")
            return
        }
        val densityDpi = resources.configuration.densityDpi
        if (densityDpi <= 0) {
            failSession("Android returned an invalid screen density for capture.")
            return
        }

        val reader = try {
            ImageReader.newInstance(
                dimensions.width,
                dimensions.height,
                PixelFormat.RGBA_8888,
                UI_CAPTURE_IMAGE_READER_MAX_IMAGES,
            )
        } catch (error: Throwable) {
            failSession("Could not allocate the bounded screen-capture buffer.")
            return
        }
        imageReader = reader
        frameHandled = false
        reader.setOnImageAvailableListener(
            { source -> handleImageAvailable(source) },
            mainHandler,
        )

        val display = try {
            resolvedProjection.createVirtualDisplay(
                "HermesBridgeOneFrame",
                dimensions.width,
                dimensions.height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                mainHandler,
            )
        } catch (error: Throwable) {
            failSession("Android rejected the one-frame screen-capture display.")
            return
        }
        if (display == null) {
            failSession("Android could not create the one-frame screen-capture display.")
            return
        }
        virtualDisplay = display

        val deadline = newUiCaptureSessionDeadline(SystemClock.elapsedRealtime())
        sessionDeadline = deadline
        UiCaptureSessionRuntime.active(deadline)
        mainHandler.removeCallbacks(expiryRunnable)
        mainHandler.postDelayed(expiryRunnable, UI_CAPTURE_SESSION_WATCHDOG_INTERVAL_MILLIS)
        mainHandler.removeCallbacks(frameTimeoutRunnable)
        mainHandler.postDelayed(frameTimeoutRunnable, UI_CAPTURE_FRAME_TIMEOUT_MILLIS)
    }

    private fun handleImageAvailable(source: ImageReader) {
        if (source !== imageReader || frameHandled || tearingDown) return

        val image = try {
            source.acquireLatestImage()
        } catch (error: Throwable) {
            frameHandled = true
            failSession("Android screen-capture buffer could not be acquired.")
            return
        } ?: return

        frameHandled = true
        mainHandler.removeCallbacks(frameTimeoutRunnable)

        val frame = try {
            val plane = image.planes.singleOrNull()
                ?: throw IllegalStateException("Unexpected screen-capture plane count.")
            copyUiCaptureRgbaPlane(
                width = image.width,
                height = image.height,
                pixelStride = plane.pixelStride,
                rowStride = plane.rowStride,
                buffer = plane.buffer,
            )
        } catch (error: Throwable) {
            runCatching { image.close() }
            failSession("Android returned an invalid or oversized screen-capture frame.")
            return
        }

        runCatching { image.close() }
        val capturedWidth = frame.width
        val capturedHeight = frame.height
        frame.erase()
        finishSession(
            "Captured one local ${capturedWidth}x${capturedHeight} frame and erased its pixel copy."
        )
    }

    private fun finishSession(message: String) {
        clearProjection(message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun failSession(message: String) {
        clearProjection(null)
        UiCaptureSessionRuntime.error(message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopWithoutSession(message: String) {
        UiCaptureSessionRuntime.error(message)
        sessionDeadline = null
        mainHandler.removeCallbacks(expiryRunnable)
        mainHandler.removeCallbacks(frameTimeoutRunnable)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun clearProjection(message: String?) {
        if (tearingDown) return
        tearingDown = true
        try {
            sessionDeadline = null
            mainHandler.removeCallbacks(expiryRunnable)
            mainHandler.removeCallbacks(frameTimeoutRunnable)

            val currentDisplay = virtualDisplay
            val currentReader = imageReader
            virtualDisplay = null
            imageReader = null
            frameHandled = false
            runCatching { currentDisplay?.release() }
            runCatching { currentReader?.setOnImageAvailableListener(null, null) }
            runCatching { currentReader?.close() }

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
            .setContentText("Захватывается один локальный кадр. Он не сохраняется и не передаётся Hermes.")
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
        private const val UI_CAPTURE_IMAGE_READER_MAX_IMAGES = 2
        private const val UI_CAPTURE_FRAME_TIMEOUT_MILLIS = 10_000L

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
