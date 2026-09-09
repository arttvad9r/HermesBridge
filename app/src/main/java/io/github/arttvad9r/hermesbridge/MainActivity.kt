package io.github.arttvad9r.hermesbridge

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HermesBridgeTheme {
                val vm: BridgeViewModel = viewModel()
                val state by vm.state.collectAsState()

                BridgeScreen(
                    state = state,
                    onCodeChange = vm::updatePairingCode,
                    onPair = vm::pair,
                    onRefreshHealth = vm::refreshHealth,
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
    onCodeChange: (String) -> Unit,
    onPair: () -> Unit,
    onRefreshHealth: () -> Unit,
) {
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

            StatusCard(state.connectionState)

            PairingCard(
                code = state.pairingCode,
                enabled = state.connectionState == ConnectionState.DISCONNECTED ||
                    state.connectionState == ConnectionState.ERROR,
                message = state.message,
                onCodeChange = onCodeChange,
                onPair = onPair,
            )

            HealthCard(
                health = state.health,
                onRefresh = onRefreshHealth,
            )

            CapabilitiesCard()

            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun StatusCard(connectionState: ConnectionState) {
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
private fun CapabilitiesCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Доступ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            CapabilityRow("Диагностика устройства", "Доступно")
            CapabilityRow("Приложения и файлы", "Запланировано")
            CapabilityRow("Расширенный доступ Shizuku", "Запланировано")
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
