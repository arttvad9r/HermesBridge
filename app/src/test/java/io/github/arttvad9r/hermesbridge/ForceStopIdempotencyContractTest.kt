package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.protocol.CommandRequestPayload
import io.github.arttvad9r.hermesbridge.protocol.CommandResultPayload
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForceStopIdempotencyContractTest {
    @Test
    fun `same force-stop request id replays terminal result without a second side effect`() = runBlocking {
        val guard = CommandReplayGuard()
        val request = CommandRequestPayload(
            tool = BridgeToolRegistry.APPS_FORCE_STOP,
            requestId = "force-stop-safe-retry",
            arguments = buildJsonObject { put("packageName", "com.example.app") },
        )
        var executions = 0

        suspend fun executeThroughGuard() = guard.execute(request) {
            executions += 1
            CommandResultPayload(
                requestId = request.requestId,
                ok = true,
                result = buildJsonObject {
                    put("packageName", "com.example.app")
                    put("forceStopped", true)
                },
            )
        }

        val first = executeThroughGuard()
        val retry = executeThroughGuard()

        assertTrue(first.result.ok)
        assertTrue(retry.result.ok)
        assertTrue(first.shouldAudit)
        assertFalse(retry.shouldAudit)
        assertEquals(first.result, retry.result)
        assertEquals(1, executions)
    }

    @Test
    fun `different request id uses a distinct replay guard entry`() = runBlocking {
        val guard = CommandReplayGuard()
        var executions = 0

        suspend fun execute(requestId: String) = guard.execute(
            CommandRequestPayload(
                tool = BridgeToolRegistry.APPS_FORCE_STOP,
                requestId = requestId,
                arguments = buildJsonObject { put("packageName", "com.example.app") },
            )
        ) {
            executions += 1
            CommandResultPayload(
                requestId = requestId,
                ok = true,
                result = buildJsonObject { put("forceStopped", true) },
            )
        }

        execute("force-stop-operation-a")
        execute("force-stop-operation-b")

        assertEquals(2, executions)
    }
}
