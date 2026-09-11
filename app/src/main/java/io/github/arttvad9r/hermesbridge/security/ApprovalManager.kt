package io.github.arttvad9r.hermesbridge.security

import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject


enum class ApprovalStatus {
    PENDING,
    APPROVED,
    DENIED,
    EXPIRED,
    CONSUMED,
}

data class ApprovalTicket(
    val id: String,
    val tool: String,
    val risk: ToolRisk,
    val argumentsFingerprint: String,
    val displaySummary: String,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val status: ApprovalStatus,
)

class ApprovalManager(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
) {
    private val tickets = LinkedHashMap<String, ApprovalTicket>()

    init {
        require(ttlMillis in MIN_TTL_MILLIS..MAX_TTL_MILLIS)
    }

    @Synchronized
    fun request(
        tool: String,
        risk: ToolRisk,
        arguments: JsonObject,
        displaySummary: String,
    ): ApprovalTicket {
        require(tool.isNotBlank())
        require(risk != ToolRisk.READ_ONLY) { "Read-only tools must not create approval tickets." }
        require(DefaultToolPolicy.decision(BridgeTool(tool, risk)) == ApprovalDecision.REQUIRE_APPROVAL) {
            "Tool is not eligible for user approval."
        }

        expireLocked()
        val fingerprint = ApprovalFingerprint.forRequest(tool, arguments)
        tickets.values.firstOrNull {
            it.argumentsFingerprint == fingerprint &&
                it.status == ApprovalStatus.PENDING &&
                it.expiresAtEpochMillis > nowMillis()
        }?.let { return it }

        val now = nowMillis()
        val ticket = ApprovalTicket(
            id = "approval_${UUID.randomUUID()}",
            tool = tool,
            risk = risk,
            argumentsFingerprint = fingerprint,
            displaySummary = sanitizeDisplaySummary(displaySummary),
            createdAtEpochMillis = now,
            expiresAtEpochMillis = now + ttlMillis,
            status = ApprovalStatus.PENDING,
        )
        tickets[ticket.id] = ticket
        pruneLocked()
        return ticket
    }

    @Synchronized
    fun approve(id: String): ApprovalTicket? = decideLocked(id, ApprovalStatus.APPROVED)

    @Synchronized
    fun deny(id: String): ApprovalTicket? = decideLocked(id, ApprovalStatus.DENIED)

    @Synchronized
    fun pending(): List<ApprovalTicket> {
        expireLocked()
        return tickets.values
            .filter { it.status == ApprovalStatus.PENDING }
            .sortedBy { it.createdAtEpochMillis }
    }

    @Synchronized
    fun find(id: String): ApprovalTicket? {
        expireLocked()
        return tickets[id]
    }

    @Synchronized
    fun consumeApproved(tool: String, arguments: JsonObject): ApprovalTicket? {
        expireLocked()
        val fingerprint = ApprovalFingerprint.forRequest(tool, arguments)
        val matching = tickets.values.firstOrNull {
            it.argumentsFingerprint == fingerprint &&
                it.tool == tool &&
                it.status == ApprovalStatus.APPROVED &&
                it.expiresAtEpochMillis > nowMillis()
        } ?: return null

        val consumed = matching.copy(status = ApprovalStatus.CONSUMED)
        tickets[matching.id] = consumed
        return consumed
    }

    @Synchronized
    fun clear() {
        tickets.clear()
    }

    private fun decideLocked(id: String, decision: ApprovalStatus): ApprovalTicket? {
        require(decision == ApprovalStatus.APPROVED || decision == ApprovalStatus.DENIED)
        expireLocked()
        val current = tickets[id] ?: return null
        // A local approval control is a one-shot decision surface. Once another tap, expiry or
        // command execution has resolved the ticket, stale UI events must not look successful or
        // generate a second audit decision for the old capability.
        if (current.status != ApprovalStatus.PENDING) return null
        val updated = current.copy(status = decision)
        tickets[id] = updated
        return updated
    }

    private fun expireLocked() {
        val now = nowMillis()
        tickets.entries.forEach { entry ->
            val ticket = entry.value
            if (
                ticket.expiresAtEpochMillis <= now &&
                (ticket.status == ApprovalStatus.PENDING || ticket.status == ApprovalStatus.APPROVED)
            ) {
                entry.setValue(ticket.copy(status = ApprovalStatus.EXPIRED))
            }
        }
    }

    private fun pruneLocked() {
        while (tickets.size > MAX_TICKETS) {
            val removable = tickets.entries.firstOrNull {
                it.value.status != ApprovalStatus.PENDING &&
                    it.value.status != ApprovalStatus.APPROVED
            } ?: tickets.entries.firstOrNull()
            if (removable == null) return
            tickets.remove(removable.key)
        }
    }

    private fun sanitizeDisplaySummary(value: String): String = value
        .asSequence()
        .map { character -> if (isUnsafeDisplayCharacter(character)) ' ' else character }
        .joinToString(separator = "")
        .trim()
        .take(MAX_SUMMARY_LENGTH)

    private fun isUnsafeDisplayCharacter(character: Char): Boolean {
        if (character.isISOControl()) return true
        return when (Character.getType(character)) {
            Character.FORMAT.toInt(),
            Character.LINE_SEPARATOR.toInt(),
            Character.PARAGRAPH_SEPARATOR.toInt(),
            -> true
            else -> false
        }
    }

    companion object {
        const val DEFAULT_TTL_MILLIS = 2 * 60 * 1000L
        const val MIN_TTL_MILLIS = 10_000L
        const val MAX_TTL_MILLIS = 10 * 60 * 1000L
        const val MAX_TICKETS = 100
        const val MAX_SUMMARY_LENGTH = 200
    }
}

object ApprovalFingerprint {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    fun forRequest(tool: String, arguments: JsonObject): String {
        require(tool.isNotBlank())
        val canonical = canonicalize(arguments)
        val payload = tool + "\n" + json.encodeToString<JsonElement>(canonical)
        return MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    internal fun canonicalize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries
                .sortedBy { it.key }
                .associateTo(LinkedHashMap()) { (key, value) -> key to canonicalize(value) }
        )
        is JsonArray -> JsonArray(element.map(::canonicalize))
        else -> element
    }
}
