package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.protocol.CommandResultPayload
import io.github.arttvad9r.hermesbridge.protocol.ProtocolError
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class RemoteCommandErrorPolicyTest {
    @Test
    fun `known error code ignores arbitrary local message`() {
        val marker = "SECRET_MARKER:/data/user/0/private"
        val result = remoteSafeCommandResult(
            CommandResultPayload(
                requestId = "request-1",
                ok = false,
                error = ProtocolError("usage_query_failed", marker),
            )
        )

        assertEquals("usage_query_failed", result.error?.code)
        assertEquals("Android could not query app usage.", result.error?.message)
        assertFalse(result.error?.message.orEmpty().contains(marker))
    }

    @Test
    fun `malformed error code and message collapse to generic failure`() {
        val marker = "SECRET_MARKER:https://relay.invalid/token"
        val result = remoteSafeCommandResult(
            CommandResultPayload(
                requestId = "request-2",
                ok = false,
                error = ProtocolError("bad code:$marker", marker),
            )
        )

        assertEquals("command_failed", result.error?.code)
        assertEquals(
            "The Android command failed. Use the structured error code to identify the failure category.",
            result.error?.message,
        )
        assertFalse(result.error?.message.orEmpty().contains(marker))
    }

    @Test
    fun `failed result without error is canonicalized and drops result payload`() {
        val marker = "SECRET_MARKER:/storage/emulated/0/private.txt"
        val result = remoteSafeCommandResult(
            CommandResultPayload(
                requestId = "request-malformed",
                ok = false,
                result = buildJsonObject { put("detail", marker) },
            )
        )

        assertNull(result.result)
        assertEquals("command_failed", result.error?.code)
        assertEquals(
            "The Android command failed. Use the structured error code to identify the failure category.",
            result.error?.message,
        )
        assertFalse(result.toString().contains(marker))
    }

    @Test
    fun `failed result with error also drops inconsistent result payload`() {
        val marker = "SECRET_MARKER:backend-private-state"
        val result = remoteSafeCommandResult(
            CommandResultPayload(
                requestId = "request-inconsistent",
                ok = false,
                result = buildJsonObject { put("detail", marker) },
                error = ProtocolError("file_operation_failed", marker),
            )
        )

        assertNull(result.result)
        assertEquals("file_operation_failed", result.error?.code)
        assertFalse(result.toString().contains(marker))
    }

    @Test
    fun `well formed successful result is unchanged`() {
        val result = CommandResultPayload(
            requestId = "request-3",
            ok = true,
            result = buildJsonObject { put("status", "ok") },
        )

        assertSame(result, remoteSafeCommandResult(result))
    }

    @Test
    fun `successful result drops inconsistent error payload`() {
        val marker = "SECRET_MARKER:should-not-cross"
        val result = remoteSafeCommandResult(
            CommandResultPayload(
                requestId = "request-success-with-error",
                ok = true,
                result = buildJsonObject { put("status", "ok") },
                error = ProtocolError("unexpected_internal_error", marker),
            )
        )

        assertNull(result.error)
        assertEquals("\"ok\"", result.result?.get("status")?.toString())
        assertFalse(result.toString().contains(marker))
    }
}
