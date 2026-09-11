package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.protocol.CommandResultPayload
import io.github.arttvad9r.hermesbridge.protocol.ProtocolError
import io.github.arttvad9r.hermesbridge.security.ApprovalStatus
import io.github.arttvad9r.hermesbridge.security.ApprovalTicket
import io.github.arttvad9r.hermesbridge.security.ToolRisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuditLogRedactionTest {
    @Test
    fun unknownToolNameIsNotPersistedVerbatim() {
        BridgeAuditRuntime.clear()
        BridgeAuditRuntime.recordCommand(
            tool = "secret-token-should-not-be-logged",
            result = CommandResultPayload(
                requestId = "request",
                ok = false,
                error = ProtocolError("unknown_tool", "ignored"),
            ),
        )

        assertEquals(UNKNOWN_TOOL, BridgeAuditRuntime.entries.value.single().tool)
    }

    @Test
    fun malformedErrorCodeIsCollapsedToGenericValue() {
        assertEquals("other_error", auditSafeErrorCode("secret value with spaces"))
        assertEquals("approval_required", auditSafeErrorCode("approval_required"))
    }

    @Test
    fun approvalAuditDoesNotPersistTargetSummary() {
        BridgeAuditRuntime.clear()
        val sensitivePath = "Documents/private/fixture-secret.txt"
        BridgeAuditRuntime.recordApproval(
            ticket = ApprovalTicket(
                id = "approval-delete",
                tool = "files.delete",
                risk = ToolRisk.MUTATING,
                argumentsFingerprint = "fingerprint",
                displaySummary = "Удалить файл $sensitivePath",
                createdAtEpochMillis = 1L,
                expiresAtEpochMillis = 2L,
                status = ApprovalStatus.APPROVED,
            ),
            outcome = BridgeAuditRuntime.APPROVAL_APPROVED,
        )

        val entry = BridgeAuditRuntime.entries.value.single()
        assertEquals("files.delete", entry.tool)
        assertNull(entry.summary)
    }

    @Test
    fun installApprovalDoesNotPersistTransportControlledSummary() {
        BridgeAuditRuntime.clear()
        val tokenLikeFileName = "A".repeat(43) + ".apk"
        BridgeAuditRuntime.recordApproval(
            ticket = ApprovalTicket(
                id = "approval-test",
                tool = "apps.install",
                risk = ToolRisk.MUTATING,
                argumentsFingerprint = "fingerprint",
                displaySummary = "Установить io.github.arttvad9r.hermesbridge.fixture 1.0 из $tokenLikeFileName",
                createdAtEpochMillis = 1L,
                expiresAtEpochMillis = 2L,
                status = ApprovalStatus.APPROVED,
            ),
            outcome = BridgeAuditRuntime.APPROVAL_APPROVED,
        )

        val entry = BridgeAuditRuntime.entries.value.single()
        assertEquals("apps.install", entry.tool)
        assertNull(entry.summary)
    }
}
