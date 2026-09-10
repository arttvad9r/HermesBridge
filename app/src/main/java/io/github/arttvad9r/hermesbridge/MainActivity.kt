package io.github.arttvad9r.hermesbridge

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arttvad9r.hermesbridge.security.ApprovalTicket
import io.github.arttvad9r.hermesbridge.security.ToolRisk
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HermesBridgeTheme {
                val vm: BridgeViewModel = viewModel()
                val state by vm.state.collectAsState()
                val uiCaptureSession by UiCaptureSessionRuntime.state.collectAsState()
                val fileTreeLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocumentTree(),
                    onResult = { uri -> uri?.let(vm::grantFileTree) },
                )
                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                    onResult = { vm.refreshNotificationPermission() },
                )

                BridgeScreen(
                    state = state,
                    uiCaptureSession = uiCaptureSession,
                    onCodeChange = vm::updatePairingCode,
                    onPair = vm::pair,
                    onRevokePairing = vm::revokePairing,
                    onFinishSetup = vm::finishSetup,
                    onRestartSetup = vm::restartSetup,
                    onRefreshHealth = vm::refreshHealth,
                    onRequestNotifications = {
                        if (requiresRuntimePermission(Build.VERSION.SDK_INT)) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            vm.refreshNotificationPermission()
                        }
                    },
                    onOpenNotificationSettings = {
                        runCatching {
                            startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                            )
                        }
                    },
                    onChooseFileTree = { fileTreeLauncher.launch(null) },
                    onRevokeFileAccess = vm::revokeFileAccess,
                    onOpenUsageAccessSettings = {
                        runCatching {
                            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        }
                    },
                    onRefreshUsageAccess = vm::refreshUsageAccess,
                    onApprove = vm::approveAction,
                    onDeny = vm::denyAction,
                    onRequestShizukuPermission = vm::requestShizukuPermission,
                    onRefreshShizuku = vm::refreshShizuku,
                    onOpenShizuku = {
                        ShizukuRestorationNotifier.shizukuLaunchIntent(this)?.let { startActivity(it) }
                    },
                    onStartUiCapture = {
                        startActivity(Intent(this, UiCaptureConsentActivity::class.java))
                    },
                    onStopUiCapture = {
                        stopService(Intent(this, UiCaptureForegroundService::class.java))
                    },
                )
            }
        }
    }
}

@Composable
private fun HermesBridgeTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }

    MaterialTheme(colorScheme = scheme) {
        Surface(modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}

@Composable
private fun BridgeScreen(
    state: BridgeUiState,
    uiCaptureSession: UiCaptureSessionState,
    onCodeChange: (String) -> Unit,
    onPair: () -> Unit,
    onRevokePairing: () -> Unit,
    onFinishSetup: () -> Unit,
    onRestartSetup: () -> Unit,
    onRefreshHealth: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onChooseFileTree: () -> Unit,
    onRevokeFileAccess: () -> Unit,
    onOpenUsageAccessSettings: () -> Unit,
    onRefreshUsageAccess: () -> Unit,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onRefreshShizuku: () -> Unit,
    onOpenShizuku: () -> Unit,
    onStartUiCapture: () -> Unit,
    onStopUiCapture: () -> Unit,
) {
    var showRevokePairingDialog by remember { mutableStateOf(false) }

    if (showRevokePairingDialog) {
        AlertDialog(
            onDismissRequest = { showRevokePairingDialog = false },
            title = { Text("Отвязать телефон?") },
            text = {
                Text(
                    "Hermes потеряет доступ к этому телефону. Чтобы подключить его снова, понадобится новый одноразовый код привязки."
                )
            },
            dismissButton = {
                TextButton(onClick = { showRevokePairingDialog = false }) {
                    Text("Отмена")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRevokePairingDialog = false
                        onRevokePairing()
                    }
                ) {
                    Text("Отвязать")
                }
            },
        )
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            Text(
                text = "Hermes Bridge",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Безопасный доступ Hermes к этому телефону.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            StatusCard(
                connectionState = state.connectionState,
                onRevokePairing = { showRevokePairingDialog = true },
            )

            state.pendingApprovals.forEach { ticket ->
                ApprovalCard(
                    ticket = ticket,
                    onApprove = { onApprove(ticket.id) },
                    onDeny = { onDeny(ticket.id) },
                )
            }

            if (!state.setupCompleted) {
                SetupHeaderCard(connected = state.connectionState == ConnectionState.CONNECTED)

                if (state.connectionState != ConnectionState.CONNECTED) {
                    PairingCard(
                        code = state.pairingCode,
                        enabled = state.connectionState == ConnectionState.DISCONNECTED ||
                            state.connectionState == ConnectionState.ERROR,
                        message = state.message,
                        onCodeChange = onCodeChange,
                        onPair = onPair,
                    )
                } else {
                    if (requiresRuntimePermission(Build.VERSION.SDK_INT)) {
                        NotificationAccessCard(
                            granted = state.notificationsGranted,
                            onRequest = onRequestNotifications,
                            onOpenSettings = onOpenNotificationSettings,
                        )
                    }

                    FileAccessCard(
                        configured = state.fileAccessConfigured,
                        onChoose = onChooseFileTree,
                        onRevoke = onRevokeFileAccess,
                    )

                    UsageAccessCard(
                        granted = state.usageAccessGranted,
                        onOpenSettings = onOpenUsageAccessSettings,
                        onRefresh = onRefreshUsageAccess,
                    )

                    ShizukuCard(
                        state = state.shizuku,
                        wasConfigured = state.shizukuWasConfigured,
                        onRequestPermission = onRequestShizukuPermission,
                        onRefresh = onRefreshShizuku,
                        onOpenShizuku = onOpenShizuku,
                    )

                    FinishSetupCard(
                        notificationsGranted = state.notificationsGranted,
                        fileAccessConfigured = state.fileAccessConfigured,
                        usageAccessGranted = state.usageAccessGranted,
                        shizukuReady = state.shizuku.status == ShizukuAccessStatus.READY,
                        onFinish = onFinishSetup,
                    )
                }
            } else {
                if (state.connectionState != ConnectionState.CONNECTED) {
                    PairingCard(
                        code = state.pairingCode,
                        enabled = state.connectionState == ConnectionState.DISCONNECTED ||
                            state.connectionState == ConnectionState.ERROR,
                        message = state.message,
                        onCodeChange = onCodeChange,
                        onPair = onPair,
                    )
                }

                HealthCard(
                    health = state.health,
                    onRefresh = onRefreshHealth,
                )

                if (requiresRuntimePermission(Build.VERSION.SDK_INT)) {
                    NotificationAccessCard(
                        granted = state.notificationsGranted,
                        onRequest = onRequestNotifications,
                        onOpenSettings = onOpenNotificationSettings,
                    )
                }

                FileAccessCard(
                    configured = state.fileAccessConfigured,
                    onChoose = onChooseFileTree,
                    onRevoke = onRevokeFileAccess,
                )

                UsageAccessCard(
                    granted = state.usageAccessGranted,
                    onOpenSettings = onOpenUsageAccessSettings,
                    onRefresh = onRefreshUsageAccess,
                )

                ShizukuCard(
                    state = state.shizuku,
                    wasConfigured = state.shizukuWasConfigured,
                    onRequestPermission = onRequestShizukuPermission,
                    onRefresh = onRefreshShizuku,
                    onOpenShizuku = onOpenShizuku,
                )

                UiCaptureCard(
                    state = uiCaptureSession,
                    onStart = onStartUiCapture,
                    onStop = onStopUiCapture,
                )

                CapabilitiesCard(
                    fileAccessConfigured = state.fileAccessConfigured,
                    usageAccessGranted = state.usageAccessGranted,
                    shizukuReady = state.shizuku.status == ShizukuAccessStatus.READY,
                )

                TextButton(
                    onClick = onRestartSetup,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Повторить мастер настройки")
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SetupHeaderCard(connected: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (connected) "Шаг 2 из 2 · Доступ" else "Шаг 1 из 2 · Подключение",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                if (connected) "Выберите, что Hermes сможет делать" else "Подключите телефон к Hermes",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (connected) {
                    "Дополнительные права можно выдать сейчас или позже. Изменяющие данные действия всё равно требуют отдельного подтверждения."
                } else {
                    "Получите одноразовый код у Hermes и введите его ниже. После этого телефон будет переподключаться автоматически."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FinishSetupCard(
    notificationsGranted: Boolean,
    fileAccessConfigured: Boolean,
    usageAccessGranted: Boolean,
    shizukuReady: Boolean,
    onFinish: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Готово к работе",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            CapabilityRow("Базовая диагностика", "Доступно")
            if (requiresRuntimePermission(Build.VERSION.SDK_INT)) {
                CapabilityRow("Уведомления", if (notificationsGranted) "Настроено" else "Рекомендуется")
            }
            CapabilityRow("Файлы", if (fileAccessConfigured) "Настроено" else "Можно позже")
            CapabilityRow("Статистика приложений", if (usageAccessGranted) "Настроено" else "Можно позже")
            CapabilityRow("Shizuku", if (shizukuReady) "Настроено" else "Можно позже")
            Text(
                "Нажмите «Завершить», чтобы перейти к обычной панели. Все эти разрешения можно изменить позже.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onFinish,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Завершить настройку")
            }
        }
    }
}

@Composable
private fun StatusCard(
    connectionState: ConnectionState,
    onRevokePairing: () -> Unit,
) {
    val (title, detail) = when (connectionState) {
        ConnectionState.DISCONNECTED -> "Не подключён" to "Введите одноразовый код Hermes."
        ConnectionState.PAIRING -> "Подключение…" to "Проверяем код и сервер."
        ConnectionState.RECONNECTING -> "Переподключение…" to "Связь с relay потеряна, соединение восстановится автоматически."
        ConnectionState.CONNECTED -> "Hermes подключён" to "Телефон доступен агенту в рамках выданных прав."
        ConnectionState.ERROR -> "Не подключён" to "Соединение не установлено."
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (connectionState == ConnectionState.CONNECTED) {
                TextButton(onClick = onRevokePairing) {
                    Text("Отвязать телефон")
                }
            }
        }
    }
}

@Composable
private fun ApprovalCard(
    ticket: ApprovalTicket,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    val riskLabel = when (ticket.risk) {
        ToolRisk.READ_ONLY -> "Чтение"
        ToolRisk.MUTATING -> "Изменение данных"
        ToolRisk.PRIVILEGED -> "Расширенный доступ"
        ToolRisk.UI_CONTROL -> "Управление интерфейсом"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Требуется подтверждение",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                riskLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                ticket.displaySummary.ifBlank { ticket.tool },
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "Разрешение одноразовое, действует недолго и подходит только для этого действия с этими параметрами.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(
                    onClick = onDeny,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Отклонить")
                }
                Button(
                    onClick = onApprove,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Разрешить")
                }
            }
        }
    }
}

@Composable
private fun PairingCard(
    code: String,
    enabled: Boolean,
    message: String?,
    onCodeChange: (String) -> Unit,
    onPair: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Подключение", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Код выдаётся Hermes и используется только для первой привязки устройства.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = code,
                onValueChange = onCodeChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                singleLine = true,
                label = { Text("Код XXXX-XXXX") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                isError = message != null,
                supportingText = message?.let { { Text(it) } },
            )
            Button(
                onClick = onPair,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
            ) {
                Text("Подключить Hermes")
            }
        }
    }
}

@Composable
private fun HealthCard(
    health: DeviceHealthSnapshot?,
    onRefresh: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Состояние телефона",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = onRefresh) {
                    Text("Обновить")
                }
            }

            if (health == null) {
                Text("Нет данных")
            } else {
                MetricRow("Батарея", health.batteryPercent?.let { "$it%" } ?: "—")
                HorizontalDivider()
                MetricRow(
                    "Свободная память",
                    formatBytes(health.availableMemoryBytes) + " / " + formatBytes(health.totalMemoryBytes),
                )
                HorizontalDivider()
                MetricRow(
                    "Свободное место",
                    formatBytes(health.availableStorageBytes) + " / " + formatBytes(health.totalStorageBytes),
                )
            }
        }
    }
}

@Composable
private fun NotificationAccessCard(
    granted: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Уведомления",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (granted) "Уведомления включены." else "Уведомления не разрешены.",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                if (granted) {
                    "Hermes Bridge может показывать статус фонового соединения, быстрый доступ к истории и напоминание восстановить Shizuku после перезагрузки."
                } else {
                    "Базовое соединение работает и без них, но Android не покажет постоянный статус Hermes и напоминание восстановить Shizuku после перезагрузки."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!granted) {
                Button(
                    onClick = onRequest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Разрешить уведомления")
                }
                TextButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Настройки уведомлений")
                }
            }
        }
    }
}

@Composable
private fun FileAccessCard(
    configured: Boolean,
    onChoose: () -> Unit,
    onRevoke: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Файлы",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (configured) {
                    "Hermes может просматривать и анализировать выбранную папку. Удаление конкретного файла или папки требует отдельного подтверждения."
                } else {
                    "Выберите папку, содержимое которой Hermes сможет просматривать и анализировать. Остальное хранилище останется недоступно."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onChoose,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (configured) "Сменить папку" else "Выбрать папку")
            }
            if (configured) {
                TextButton(
                    onClick = onRevoke,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Отключить доступ к файлам")
                }
            }
        }
    }
}

@Composable
private fun UsageAccessCard(
    granted: Boolean,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Использование приложений",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = onRefresh) {
                    Text("Проверить")
                }
            }
            Text(
                if (granted) "Usage Access выдан." else "Usage Access не выдан.",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "Нужен только для чтения времени последнего использования и времени на переднем плане у приложений, уже видимых Hermes. Разрешение включается вручную в Android.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!granted) {
                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Открыть Usage Access")
                }
            }
        }
    }
}

@Composable
private fun ShizukuCard(
    state: ShizukuAccessState,
    wasConfigured: Boolean,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit,
    onOpenShizuku: () -> Unit,
) {
    val title = when (state.status) {
        ShizukuAccessStatus.UNAVAILABLE -> if (wasConfigured) "Shizuku нужно запустить снова" else "Shizuku не запущен"
        ShizukuAccessStatus.UNSUPPORTED -> "Shizuku устарел"
        ShizukuAccessStatus.PERMISSION_REQUIRED -> "Shizuku готов к авторизации"
        ShizukuAccessStatus.DENIED -> "Доступ Shizuku не выдан"
        ShizukuAccessStatus.READY -> "Shizuku подключён"
    }
    val message = if (wasConfigured && state.status == ShizukuAccessStatus.UNAVAILABLE) {
        "После перезагрузки Android запустите Shizuku снова. Базовое соединение Hermes уже работает; восстановить нужно только расширенные команды."
    } else {
        state.message
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Расширенный доступ",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = onRefresh) {
                    Text("Проверить")
                }
            }
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.status == ShizukuAccessStatus.READY) {
                val mode = when (state.serverUid) {
                    0 -> "root"
                    2000 -> "ADB/shell"
                    null -> "неизвестно"
                    else -> "UID ${state.serverUid}"
                }
                MetricRow("API Shizuku", state.serverApiVersion?.toString() ?: "—")
                HorizontalDivider()
                MetricRow("Режим", mode)
            }
            if (state.status == ShizukuAccessStatus.PERMISSION_REQUIRED) {
                Button(
                    onClick = onRequestPermission,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Выдать доступ Hermes Bridge")
                }
            }
            if (wasConfigured && state.status == ShizukuAccessStatus.UNAVAILABLE) {
                Button(
                    onClick = onOpenShizuku,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Открыть Shizuku")
                }
            }
        }
    }
}

@Composable
private fun UiCaptureCard(
    state: UiCaptureSessionState,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val status = when (state.status) {
        UiCaptureSessionStatus.STOPPED -> "Не активен"
        UiCaptureSessionStatus.REQUESTING_CONSENT -> "Ожидается подтверждение Android"
        UiCaptureSessionStatus.STARTING -> "Запускается"
        UiCaptureSessionStatus.ACTIVE -> "Активен"
        UiCaptureSessionStatus.DENIED -> "Разрешение не выдано"
        UiCaptureSessionStatus.ERROR -> "Ошибка запуска"
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Захват экрана",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(status, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                if (state.status == UiCaptureSessionStatus.ACTIVE) {
                    "Сессия действует не более пяти минут и может быть остановлена здесь в любой момент. Hermes пока не получает изображение."
                } else {
                    "Каждая новая сессия требует системного подтверждения Android. Hermes пока не получает изображение: передача скриншотов ещё не включена."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                canRequestUiCaptureConsent(state.status) -> {
                    Button(
                        onClick = onStart,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Начать захват")
                    }
                }

                state.status == UiCaptureSessionStatus.STARTING ||
                    state.status == UiCaptureSessionStatus.ACTIVE -> {
                    Button(
                        onClick = onStop,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Остановить захват")
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun CapabilitiesCard(
    fileAccessConfigured: Boolean,
    usageAccessGranted: Boolean,
    shizukuReady: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Доступ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            CapabilityRow("Диагностика устройства", "Доступно")
            CapabilityRow("Список приложений", "Доступно")
            CapabilityRow("Статистика использования", if (usageAccessGranted) "Доступно" else "Не настроено")
            CapabilityRow("Файлы", if (fileAccessConfigured) "Доступно" else "Не настроено")
            CapabilityRow("Подтверждение действий", "Готово")
            CapabilityRow("Расширенный доступ Shizuku", if (shizukuReady) "Готово" else "Не настроено")
            CapabilityRow("Управление интерфейсом", "Опционально")
        }
    }
}

@Composable
private fun CapabilityRow(label: String, state: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            state,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 Б"
    val gib = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    return String.format(Locale.getDefault(), "%.1f ГБ", gib)
}