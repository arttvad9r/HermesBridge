package io.github.arttvad9r.hermesbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class BridgeForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var commandJob: Job? = null
    private var shizukuRestorationJob: Job? = null
    private lateinit var transport: RelayAgentTransport
    private lateinit var advancedAccessStore: AdvancedAccessStore

    override fun onCreate() {
        super.onCreate()
        BridgeAuditRuntime.initialize(applicationContext)
        advancedAccessStore = AdvancedAccessStore(applicationContext)
        createNotificationChannel()

        val healthRepository = AndroidDeviceHealthRepository(applicationContext)
        transport = RelayAgentTransport(
            context = applicationContext,
            relayWsUrl = BuildConfig.RELAY_WS_URL,
            healthRepository = healthRepository,
        )

        startAsForeground(
            title = "Hermes Bridge",
            text = "Подготовка защищённого соединения…",
        )

        serviceScope.launch {
            transport.connectionState.collectLatest { state ->
                if (state == ConnectionState.ERROR && BridgeRuntime.state.value.message != null) {
                    return@collectLatest
                }
                onTransportState(state, null)
            }
        }

        serviceScope.launch {
            ShizukuRuntime.state.collectLatest { shizuku ->
                if (shizuku.status == ShizukuAccessStatus.READY) {
                    advancedAccessStore.markConfigured()
                    ShizukuRestorationNotifier.cancel(applicationContext)
                } else if (UiControlSessionRuntime.state.value.status == UiControlSessionStatus.ACTIVE) {
                    UiControlSessionRuntime.stopLocalSession(
                        UiControlSessionStopReason.SHIZUKU_UNAVAILABLE.displayMessage(),
                    )
                }
                refreshForegroundNotification()
            }
        }

        serviceScope.launch {
            UiControlSessionRuntime.state.collectLatest { session ->
                refreshForegroundNotification()
                if (session.status != UiControlSessionStatus.ACTIVE) return@collectLatest
                monitorUiControlSession()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_CONNECT) {
            ACTION_PAIR -> {
                val code = intent?.getStringExtra(EXTRA_PAIRING_CODE)
                if (code.isNullOrBlank()) {
                    onTransportState(ConnectionState.ERROR, "Не передан код привязки.")
                } else {
                    launchCommand { transport.pair(code) }
                }
            }

            ACTION_CONNECT -> launchCommand { transport.resume() }
            ACTION_CONNECT_AFTER_BOOT -> {
                launchCommand { transport.resume() }
                scheduleShizukuRestorationCheck()
            }
            ACTION_REVOKE -> revokePairing()
            ACTION_STOP -> stopBridge()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        commandJob?.cancel()
        shizukuRestorationJob?.cancel()
        serviceScope.cancel()
        BridgeRuntime.update(ConnectionState.DISCONNECTED)
        UiControlSessionRuntime.stopLocalSession(
            "UI-control session stopped with Hermes Bridge.",
        )
        transport.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun launchCommand(block: suspend () -> Result<Unit>) {
        commandJob?.cancel()
        BridgeRuntime.update(ConnectionState.PAIRING)
        commandJob = serviceScope.launch {
            val result = block()
            result.onFailure { error ->
                onTransportState(
                    ConnectionState.ERROR,
                    error.message ?: "Не удалось подключиться к relay.",
                )
            }
        }
    }

    private fun scheduleShizukuRestorationCheck() {
        shizukuRestorationJob?.cancel()
        shizukuRestorationJob = serviceScope.launch {
            delay(SHIZUKU_RESTORE_CHECK_DELAY_MILLIS)
            ShizukuRuntime.refresh()
            ShizukuRestorationNotifier.showIfNeeded(
                applicationContext,
                ShizukuRuntime.state.value.status,
            )
        }
    }

    private suspend fun monitorUiControlSession() {
        while (UiControlSessionRuntime.state.value.status == UiControlSessionStatus.ACTIVE) {
            val session = UiControlSessionRuntime.state.value
            val now = SystemClock.elapsedRealtime()
            val stopReason = uiControlSessionStopReason(
                sessionState = session,
                nowElapsedRealtimeMillis = now,
                connectionState = BridgeRuntime.state.value.connectionState,
                shizukuStatus = ShizukuRuntime.state.value.status,
                notificationVisible = BridgeNotificationPermission.isBridgeStatusVisible(applicationContext),
            )
            if (stopReason != null) {
                UiControlSessionRuntime.stopLocalSession(stopReason.displayMessage())
                return
            }

            val expiresAt = session.expiresAtElapsedRealtimeMillis ?: now
            val remaining = (expiresAt - now).coerceAtLeast(1L)
            delay(minOf(UI_CONTROL_SESSION_WATCHDOG_INTERVAL_MILLIS, remaining))
        }
    }

    private fun revokePairing() {
        commandJob?.cancel()
        commandJob = serviceScope.launch {
            transport.revokePairing()
                .onSuccess {
                    BridgeRuntime.update(ConnectionState.DISCONNECTED)
                    UiControlSessionRuntime.stopLocalSession(
                        "UI-control session stopped because the phone was unpaired.",
                    )
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                .onFailure { error ->
                    onTransportState(
                        ConnectionState.ERROR,
                        error.message ?: "Не удалось отозвать привязку телефона.",
                    )
                }
        }
    }

    private fun stopBridge() {
        commandJob?.cancel()
        BridgeRuntime.update(ConnectionState.DISCONNECTED)
        UiControlSessionRuntime.stopLocalSession(
            "UI-control session stopped because Hermes Bridge was stopped.",
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun onTransportState(state: ConnectionState, message: String?) {
        BridgeRuntime.update(state, message)
        if (state != ConnectionState.CONNECTED &&
            UiControlSessionRuntime.state.value.status == UiControlSessionStatus.ACTIVE
        ) {
            UiControlSessionRuntime.stopLocalSession(
                UiControlSessionStopReason.BRIDGE_DISCONNECTED.displayMessage(),
            )
        }
        startAsForeground("Hermes Bridge", transportNotificationText(state, message))
    }

    private fun refreshForegroundNotification() {
        val runtime = BridgeRuntime.state.value
        startAsForeground(
            "Hermes Bridge",
            transportNotificationText(runtime.connectionState, runtime.message),
        )
    }

    private fun transportNotificationText(state: ConnectionState, message: String?): String =
        when (state) {
            ConnectionState.DISCONNECTED -> "Соединение остановлено"
            ConnectionState.PAIRING -> "Подключение к Hermes…"
            ConnectionState.RECONNECTING -> "Соединение потеряно. Переподключение…"
            ConnectionState.CONNECTED -> "Hermes подключён к телефону"
            ConnectionState.ERROR -> message ?: "Ошибка соединения"
        }

    private fun startAsForeground(title: String, text: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(title, text),
            type,
        )
    }

    private fun buildNotification(title: String, text: String): Notification {
        val bridgeConnected = BridgeRuntime.state.value.connectionState == ConnectionState.CONNECTED
        val shizukuReady = ShizukuRuntime.state.value.status == ShizukuAccessStatus.READY
        val uiControlActive =
            UiControlSessionRuntime.state.value.status == UiControlSessionStatus.ACTIVE
        val contentTarget = if (bridgeConnected && shizukuReady) {
            UiControlSessionActivity::class.java
        } else {
            MainActivity::class.java
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, contentTarget),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, BridgeForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val historyIntent = PendingIntent.getActivity(
            this,
            2,
            Intent(this, AuditLogActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val captureConsentIntent = PendingIntent.getActivity(
            this,
            3,
            Intent(this, UiCaptureConsentActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val visibleText = when {
            uiControlActive -> "$text · UI-контроль включён максимум на 5 минут; нажмите для управления"
            bridgeConnected && shizukuReady -> "$text · Нажмите для локальной UI-сессии"
            else -> text
        }

        return NotificationCompat.Builder(this, BRIDGE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bridge_notification)
            .setContentTitle(title)
            .setContentText(visibleText)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "История", historyIntent)
            .addAction(0, "Захват экрана", captureConsentIntent)
            .addAction(0, "Остановить", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            BRIDGE_NOTIFICATION_CHANNEL_ID,
            "Hermes Bridge",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Постоянное защищённое соединение Hermes с телефоном"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_CONNECT = "io.github.arttvad9r.hermesbridge.CONNECT"
        private const val ACTION_CONNECT_AFTER_BOOT = "io.github.arttvad9r.hermesbridge.CONNECT_AFTER_BOOT"
        private const val ACTION_PAIR = "io.github.arttvad9r.hermesbridge.PAIR"
        private const val ACTION_REVOKE = "io.github.arttvad9r.hermesbridge.REVOKE_PAIRING"
        private const val ACTION_STOP = "io.github.arttvad9r.hermesbridge.STOP"
        private const val EXTRA_PAIRING_CODE = "pairing_code"
        private const val SHIZUKU_RESTORE_CHECK_DELAY_MILLIS = 8_000L
        private const val UI_CONTROL_SESSION_WATCHDOG_INTERVAL_MILLIS = 1_000L

        fun connect(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BridgeForegroundService::class.java).setAction(ACTION_CONNECT),
            )
        }

        fun connectAfterBoot(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BridgeForegroundService::class.java).setAction(ACTION_CONNECT_AFTER_BOOT),
            )
        }

        fun pair(context: Context, code: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BridgeForegroundService::class.java)
                    .setAction(ACTION_PAIR)
                    .putExtra(EXTRA_PAIRING_CODE, code),
            )
        }

        fun revokePairing(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BridgeForegroundService::class.java).setAction(ACTION_REVOKE),
            )
        }
    }
}
