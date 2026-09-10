package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.protocol.CommandRequestPayload
import io.github.arttvad9r.hermesbridge.protocol.CommandResultPayload
import io.github.arttvad9r.hermesbridge.protocol.ProtocolError
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandReplayGuardTest {
    @Test
    fun `terminal non-idempotent result is replayed without executing twice`() = runBlocking {
        val guard = CommandReplayGuard()
        val executions = AtomicInteger()
        val request = request("apps.forceStop", "req-terminal", "packageName", "com.example.app")

        val first = guard.execute(request) {
            executions.incrementAndGet()
            success(request.requestId)
        }
        val second = guard.execute(request) {
            executions.incrementAndGet()
            error("duplicate execution")
        }

        assertEquals(1, executions.get())
        assertTrue(first.result.ok)
        assertTrue(first.shouldAudit)
        assertEquals(first.result, second.result)
        assertFalse(second.shouldAudit)
    }

    @Test
    fun `approval required remains retryable for the exact same request`() = runBlocking {
        val guard = CommandReplayGuard()
        val executions = AtomicInteger()
        val request = request("files.delete", "req-approval", "path", "example.txt")

        repeat(2) {
            val outcome = guard.execute(request) {
                executions.incrementAndGet()
                failure(request.requestId, "approval_required")
            }
            assertEquals("approval_required", outcome.result.error?.code)
            assertTrue(outcome.shouldAudit)
        }

        assertEquals(2, executions.get())
    }

    @Test
    fun `same request id cannot be rebound to different canonical arguments`() = runBlocking {
        val guard = CommandReplayGuard()
        val first = request("files.delete", "req-conflict", "path", "a.txt")
        val conflicting = request("files.delete", "req-conflict", "path", "b.txt")

        guard.execute(first) { failure(first.requestId, "approval_required") }
        val outcome = guard.execute(conflicting) { error("conflicting request must not execute") }

        assertEquals("request_id_conflict", outcome.result.error?.code)
        assertTrue(outcome.shouldAudit)
    }

    @Test
    fun `concurrent exact duplicates share one in-flight execution`() = runBlocking {
        val guard = CommandReplayGuard()
        val executions = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val request = request("apps.uninstall", "req-concurrent", "packageName", "com.example.app")

        val first = async {
            guard.execute(request) {
                executions.incrementAndGet()
                started.complete(Unit)
                release.await()
                success(request.requestId)
            }
        }
        started.await()
        val second = async {
            guard.execute(request) {
                executions.incrementAndGet()
                error("duplicate execution")
            }
        }

        assertEquals(1, executions.get())
        release.complete(Unit)

        val firstOutcome = first.await()
        val secondOutcome = second.await()
        assertEquals(1, executions.get())
        assertTrue(firstOutcome.shouldAudit)
        assertFalse(secondOutcome.shouldAudit)
        assertEquals(firstOutcome.result, secondOutcome.result)
    }

    @Test
    fun `read-only duplicate may execute again but request id stays payload-bound`() = runBlocking {
        val guard = CommandReplayGuard()
        val executions = AtomicInteger()
        val request = CommandRequestPayload(tool = "device.health", requestId = "req-read")

        repeat(2) {
            val outcome = guard.execute(request) {
                executions.incrementAndGet()
                success(request.requestId)
            }
            assertTrue(outcome.result.ok)
            assertTrue(outcome.shouldAudit)
        }

        assertEquals(2, executions.get())
    }

    @Test
    fun `invalid request id fails closed before tool execution`() = runBlocking {
        val guard = CommandReplayGuard()
        val request = CommandRequestPayload(tool = "device.health", requestId = "bad request id")

        val outcome = guard.execute(request) { error("invalid request must not execute") }

        assertEquals("invalid_request_id", outcome.result.error?.code)
        assertTrue(outcome.shouldAudit)
    }

    private fun request(
        tool: String,
        requestId: String,
        argumentName: String,
        argumentValue: String,
    ) = CommandRequestPayload(
        tool = tool,
        requestId = requestId,
        arguments = buildJsonObject { put(argumentName, argumentValue) },
    )

    private fun success(requestId: String) = CommandResultPayload(
        requestId = requestId,
        ok = true,
        result = buildJsonObject { put("status", "ok") },
    )

    private fun failure(requestId: String, code: String) = CommandResultPayload(
        requestId = requestId,
        ok = false,
        error = ProtocolError(code, "test"),
    )
}
