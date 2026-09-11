package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.protocol.CommandResultPayload
import io.github.arttvad9r.hermesbridge.protocol.ProtocolError
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditLogTest {
    @Test
    fun auditLogEntriesRoundTripThroughSerialization() {
        val original = listOf(entry(7))

        val encoded = Json.encodeToString(original)
        val decoded = Json.decodeFromString<List<AuditLogEntry>>(encoded)

        assertEquals(original, decoded)
    }

    @Test
    fun boundedHistoryKeepsNewestTwoHundredEntries() {
        var entries = emptyList<AuditLogEntry>()
        repeat(MAX_AUDIT_ENTRIES + 25) { index ->
            entries = prependBoundedAuditEntry(entry(index), entries)
        }

        assertEquals(MAX_AUDIT_ENTRIES, entries.size)
        assertEquals("224", entries.first().id)
        assertEquals("25", entries.last().id)
    }

    @Test
    fun duplicateEntryIdIsNotRetainedTwice() {
        val old = listOf(entry(1), entry(2))
        val replacement = entry(1).copy(outcome = "error")

        val next = prependBoundedAuditEntry(replacement, old)

        assertEquals(listOf("1", "2"), next.map { it.id })
        assertEquals("error", next.first().outcome)
    }

    @Test
    fun auditTextRemovesControlCharactersCollapsesWhitespaceAndTruncates() {
        val sanitized = sanitizeAuditText("  line1\n\tline2\u0000   secret  ", 12)

        assertEquals("line1 line2", sanitized)
        assertTrue(sanitized.none(Char::isISOControl))
    }

    @Test
    fun commandAuditStoresOutcomeMetadataButNoArgumentSummary() {
        BridgeAuditRuntime.clear()
        BridgeAuditRuntime.recordCommand(
            tool = "apps.revokePermission",
            result = CommandResultPayload(
                requestId = "request",
                ok = false,
                error = ProtocolError("approval_required", "contains-sensitive-detail"),
            ),
        )

        val entry = BridgeAuditRuntime.entries.value.single()
        assertEquals(AuditEventType.COMMAND, entry.type)
        assertEquals("apps.revokePermission", entry.tool)
        assertEquals("error", entry.outcome)
        assertEquals("approval_required", entry.errorCode)
        assertNull(entry.summary)
    }

    @Test
    fun uiControlSessionAuditStoresLifecycleOnlyWithoutActionArguments() {
        BridgeAuditRuntime.clear()
        BridgeAuditRuntime.recordUiControlSession(BridgeAuditRuntime.UI_CONTROL_SESSION_STARTED)

        val entry = BridgeAuditRuntime.entries.value.single()
        assertEquals(AuditEventType.SESSION, entry.type)
        assertEquals(UI_CONTROL_SESSION_AUDIT_TOOL, entry.tool)
        assertEquals(BridgeAuditRuntime.UI_CONTROL_SESSION_STARTED, entry.outcome)
        assertNull(entry.errorCode)
        assertNull(entry.summary)
    }

    private fun entry(index: Int) = AuditLogEntry(
        id = index.toString(),
        timestampEpochMillis = index.toLong(),
        type = AuditEventType.COMMAND,
        tool = "device.health",
        outcome = "success",
    )
}
