package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.protocol.CommandRequestPayload
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeToolRegistryTest {
    private val repository = object : DeviceHealthRepository {
        override fun snapshot() = DeviceHealthSnapshot(
            batteryPercent = 73,
            availableMemoryBytes = 10L,
            totalMemoryBytes = 20L,
            availableStorageBytes = 30L,
            totalStorageBytes = 40L,
        )
    }
    private val registry = BridgeToolRegistry(repository)

    @Test
    fun deviceHealthExecutesWithoutApproval() = runBlocking {
        val result = registry.execute(
            CommandRequestPayload(
                tool = BridgeToolRegistry.DEVICE_HEALTH,
                requestId = "request-1",
            )
        )

        assertTrue(result.ok)
        assertEquals("73", result.result?.get("batteryPercent")?.toString())
    }

    @Test
    fun unknownToolFailsClosed() = runBlocking {
        val result = registry.execute(
            CommandRequestPayload(
                tool = "run_shell",
                requestId = "request-2",
            )
        )

        assertFalse(result.ok)
        assertEquals("unknown_tool", result.error?.code)
    }

    @Test
    fun deviceHealthRejectsArguments() = runBlocking {
        val result = registry.execute(
            CommandRequestPayload(
                tool = BridgeToolRegistry.DEVICE_HEALTH,
                requestId = "request-3",
                arguments = buildJsonObject { put("unexpected", true) },
            )
        )

        assertFalse(result.ok)
        assertEquals("invalid_arguments", result.error?.code)
    }
}
