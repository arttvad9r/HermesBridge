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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AuditLogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BridgeAuditRuntime.initialize(applicationContext)
        enableEdgeToEdge()
        setContent {
            AuditLogTheme {
                val entries by BridgeAuditRuntime.entries.collectAsState()
                AuditLogScreen(
                    entries = entries,
                    onBack = ::finish,
                    onClear = BridgeAuditRuntime::clear,
                )
            }
        }
    }
}

@Composable
private fun AuditLogTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme) {
        Surface(modifier = Modifier.fillMaxSize()) { content() }
    }
}

@Composable
private fun AuditLogScreen(
    entries: List<AuditLogEntry>,
    onBack: () -> Unit,
    onClear: () -> Unit,
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "История Hermes",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = onBack) { Text("Назад") }
            }
            Text(
                "Последние ${MAX_AUDIT_ENTRIES} технических событий хранятся только на телефоне. Аргументы команд, токены и содержимое файлов в журнал не записываются.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (entries.isEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "История пока пуста.",
                        modifier = Modifier.padding(18.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                entries.forEach { entry ->
                    AuditEntryCard(entry)
                }
                HorizontalDivider()
                TextButton(
                    onClick = onClear,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Очистить историю")
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun AuditEntryCard(entry: AuditLogEntry) {
    val title = when (entry.type) {
        AuditEventType.COMMAND -> auditToolLabel(entry.tool)
        AuditEventType.APPROVAL -> when (entry.outcome) {
            BridgeAuditRuntime.APPROVAL_APPROVED -> "Действие разрешено"
            BridgeAuditRuntime.APPROVAL_DENIED -> "Действие отклонено"
            else -> "Решение по действию"
        }
        AuditEventType.SESSION -> when (entry.outcome) {
            BridgeAuditRuntime.UI_CONTROL_SESSION_STARTED -> "UI-контроль включён"
            BridgeAuditRuntime.UI_CONTROL_SESSION_STOPPED -> "UI-контроль выключен"
            else -> "Событие UI-контроля"
        }
    }
    val status = when {
        entry.type == AuditEventType.APPROVAL -> entry.summary ?: auditToolLabel(entry.tool)
        entry.type == AuditEventType.SESSION -> "Локальная короткоживущая сессия"
        entry.outcome == "success" -> "Выполнено"
        entry.errorCode == "approval_required" -> "Ожидает подтверждения"
        entry.errorCode != null -> "Ошибка: ${entry.errorCode}"
        else -> "Ошибка"
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(
                    formatAuditTimestamp(entry.timestampEpochMillis),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                entry.tool,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun auditToolLabel(tool: String): String = when (tool) {
    "device.health" -> "Состояние телефона"
    "battery.usage" -> "Диагностика батареи"
    "apps.list" -> "Список приложений"
    "apps.usage" -> "Статистика приложений"
    "apps.permissions" -> "Разрешения приложения"
    "apps.permissionsAudit" -> "Аудит разрешений"
    "apps.revokePermission" -> "Отзыв разрешения"
    "files.list" -> "Просмотр файлов"
    "files.analyze" -> "Анализ файлов"
    "files.delete" -> "Удаление файла"
    "apps.install" -> "Установка приложения"
    "apps.uninstall" -> "Удаление приложения"
    "apps.forceStop" -> "Остановка приложения"
    UI_CONTROL_SESSION_AUDIT_TOOL -> "Сессия UI-контроля"
    else -> tool
}

private fun formatAuditTimestamp(epochMillis: Long): String =
    SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(epochMillis))
