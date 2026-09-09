package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.protocol.CommandResultPayload
import io.github.arttvad9r.hermesbridge.protocol.ProtocolError
import org.junit.Assert.assertEquals
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
}
