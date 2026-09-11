package io.github.arttvad9r.hermesbridge

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Non-exported local UI for activating/revoking the short-lived UI-control capability.
 *
 * This screen exposes no tap/swipe parameters and cannot be launched by Hermes. The privileged
 * capability can start only while the bridge is connected, Shizuku is ready, and the bridge
 * notification is actually visible so its background lifetime remains user-observable.
 */
class UiControlSessionActivity : ComponentActivity() {
    private val bridgeNotificationVisible = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bridgeNotificationVisible.value = BridgeNotificationPermission.isBridgeStatusVisible(this)
        ShizukuRuntime.refresh()

        setContent {
            MaterialTheme {
                val bridge by BridgeRuntime.state.collectAsState()
                val shizuku by ShizukuRuntime.state.collectAsState()
                val session by UiControlSessionRuntime.state.collectAsState()
                val notificationVisible by bridgeNotificationVisible.collectAsState()

                UiControlSessionScreen(
                    session = session,
                    connectionState = bridge.connectionState,
                    shizukuStatus = shizuku.status,
                    notificationVisible = notificationVisible,
                    onStart = {
                        if (
                            canStartUiControlSession(
                                sessionStatus = UiControlSessionRuntime.state.value.status,
                                connectionState = BridgeRuntime.state.value.connectionState,
                                shizukuStatus = ShizukuRuntime.state.value.status,
                                notificationVisible = BridgeNotificationPermission.isBridgeStatusVisible(this),
                            )
                        ) {
                            UiControlSessionRuntime.startLocalSession()
                        }
                    },
                    onStop = {
                        UiControlSessionRuntime.stopLocalSession(
                            "UI-control session stopped locally.",
                        )
                    },
                    onOpenBridge = {
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        bridgeNotificationVisible.value = BridgeNotificationPermission.isBridgeStatusVisible(this)
        ShizukuRuntime.refresh()
    }
}

@Composable
private fun UiControlSessionScreen(
    session: UiControlSessionState,
    connectionState: ConnectionState,
    shizukuStatus: ShizukuAccessStatus,
    notificationVisible: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenBridge: () -> Unit,
) {
    val canStart = canStartUiControlSession(
        sessionStatus = session.status,
        connectionState = connectionState,
        shizukuStatus = shizukuStatus,
        notificationVisible = notificationVisible,
    )

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Управление интерфейсом",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Эта локальная сессия разрешает только узкие typed tap/swipe операции прототипа. Она не включает raw shell и не создаёт удалённый инструмент сама по себе.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        if (session.status == UiControlSessionStatus.ACTIVE) {
                            "Сессия активна"
                        } else {
                            "Сессия выключена"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        if (connectionState == ConnectionState.CONNECTED) {
                            "Hermes: подключён"
                        } else {
                            "Hermes: требуется подключение"
                        }
                    )
                    Text(
                        if (shizukuStatus == ShizukuAccessStatus.READY) {
                            "Shizuku: готов"
                        } else {
                            "Shizuku: требуется готовое подключение"
                        }
                    )
                    Text(
                        if (notificationVisible) {
                            "Фоновый статус: видим"
                        } else {
                            "Фоновый статус: включите уведомления и канал Hermes Bridge"
                        }
                    )
                    session.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }

            if (session.status == UiControlSessionStatus.ACTIVE) {
                Text(
                    "Сессия автоматически прекращается максимум через пять минут и немедленно отзывается при потере соединения, Shizuku или видимости фонового статуса.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Отключить управление")
                }
            } else {
                Button(
                    onClick = onStart,
                    enabled = canStart,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Включить на 5 минут")
                }
            }

            TextButton(
                onClick = onOpenBridge,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Открыть Hermes Bridge")
            }
        }
    }
}
