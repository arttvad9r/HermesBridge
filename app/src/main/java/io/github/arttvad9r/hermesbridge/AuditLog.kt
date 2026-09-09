package io.github.arttvad9r.hermesbridge

import android.content.Context
import io.github.arttvad9r.hermesbridge.protocol.CommandResultPayload
import io.github.arttvad9r.hermesbridge.security.ApprovalTicket
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class AuditEventType {
    COMMAND,
    APPROVAL,
}

@Serializable
data class AuditLogEntry(
    val id: String,
    val timestampEpochMillis: Long,
    val type: AuditEventType,
    val tool: String,
    val outcome: String,
    val errorCode: String? = null,
    val summary: String? = null,
)

class AuditLogStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val lock = Any()

    fun load(): List<AuditLogEntry> = synchronized(lock) {
        loadLocked()
    }

    fun append(entry: AuditLogEntry): List<AuditLogEntry> = synchronized(lock) {
        val next = prependBoundedAuditEntry(entry, loadLocked())
        prefs.edit().putString(KEY_ENTRIES, json.encodeToString(next)).apply()
        next
    }

    fun clear() = synchronized(lock) {
        prefs.edit().remove(KEY_ENTRIES).apply()
    }

    private fun loadLocked(): List<AuditLogEntry> {
        val encoded = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<AuditLogEntry>>(encoded) }
            .getOrDefault(emptyList())
            .take(MAX_AUDIT_ENTRIES)
    }

    companion object {
        private const val PREFS_NAME = "hermes_bridge_audit"
        private const val KEY_ENTRIES = "entries_json"
    }
}

object BridgeAuditRuntime {
    private val lock = Any()
    private val mutableEntries = MutableStateFlow<List<AuditLogEntry>>(emptyList())
    private var store: AuditLogStore? = null

    val entries: StateFlow<List<AuditLogEntry>> = mutableEntries.asStateFlow()

    fun initialize(context: Context) {
        synchronized(lock) {
            if (store != null) return
            val created = AuditLogStore(context)
            store = created
            val inMemory = mutableEntries.value
            mutableEntries.value = if (inMemory.isEmpty()) {
                created.load()
            } else {
                var merged = created.load()
                inMemory.asReversed().forEach { entry ->
                    merged = created.append(entry)
                }
                merged
            }
        }
    }

    fun recordCommand(tool: String, result: CommandResultPayload) {
        append(
            AuditLogEntry(
                id = UUID.randomUUID().toString(),
                timestampEpochMillis = System.currentTimeMillis(),
                type = AuditEventType.COMMAND,
                tool = auditSafeToolName(tool),
                outcome = if (result.ok) OUTCOME_SUCCESS else OUTCOME_ERROR,
                errorCode = result.error?.code?.let(::auditSafeErrorCode),
            )
        )
    }

    fun recordApproval(ticket: ApprovalTicket, outcome: String) {
        val safeTool = auditSafeToolName(ticket.tool)
        append(
            AuditLogEntry(
                id = UUID.randomUUID().toString(),
                timestampEpochMillis = System.currentTimeMillis(),
                type = AuditEventType.APPROVAL,
                tool = safeTool,
                outcome = sanitizeAuditText(outcome, MAX_OUTCOME_LENGTH),
                summary = if (safeTool == UNKNOWN_TOOL) {
                    null
                } else {
                    sanitizeAuditText(ticket.displaySummary, MAX_SUMMARY_LENGTH)
                        .takeIf(String::isNotEmpty)
                },
            )
        )
    }

    fun clear() {
        synchronized(lock) {
            store?.clear()
            mutableEntries.value = emptyList()
        }
    }

    private fun append(entry: AuditLogEntry) {
        synchronized(lock) {
            val activeStore = store
            mutableEntries.value = if (activeStore == null) {
                prependBoundedAuditEntry(entry, mutableEntries.value)
            } else {
                activeStore.append(entry)
            }
        }
    }

    const val APPROVAL_APPROVED = "approved"
    const val APPROVAL_DENIED = "denied"
    private const val OUTCOME_SUCCESS = "success"
    private const val OUTCOME_ERROR = "error"
    private const val MAX_OUTCOME_LENGTH = 32
    private const val MAX_SUMMARY_LENGTH = 180
}

internal fun auditSafeToolName(tool: String): String =
    tool.takeIf(AUDITED_TOOL_NAMES::contains) ?: UNKNOWN_TOOL

internal fun auditSafeErrorCode(code: String): String {
    val normalized = sanitizeAuditText(code, 64)
    return normalized.takeIf { AUDIT_ERROR_CODE_REGEX.matches(it) } ?: "other_error"
}

internal fun prependBoundedAuditEntry(
    entry: AuditLogEntry,
    existing: List<AuditLogEntry>,
): List<AuditLogEntry> = buildList(capacity = minOf(MAX_AUDIT_ENTRIES, existing.size + 1)) {
    add(entry)
    existing.asSequence()
        .filterNot { it.id == entry.id }
        .take(MAX_AUDIT_ENTRIES - 1)
        .forEach(::add)
}

internal fun sanitizeAuditText(value: String, maxLength: Int): String {
    require(maxLength > 0)
    return value
        .asSequence()
        .map { character -> if (character.isISOControl()) ' ' else character }
        .joinToString(separator = "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(maxLength)
        .trim()
}

internal const val MAX_AUDIT_ENTRIES = 200
internal const val UNKNOWN_TOOL = "unknown_tool"

private val AUDIT_ERROR_CODE_REGEX = Regex("^[a-z0-9_]{1,64}$")
private val AUDITED_TOOL_NAMES = setOf(
    "device.health",
    "battery.usage",
    "apps.list",
    "apps.usage",
    "apps.permissions",
    "apps.permissionsAudit",
    "apps.revokePermission",
    "files.list",
    "files.analyze",
    "files.delete",
    "apps.install",
    "apps.uninstall",
    "apps.forceStop",
)
