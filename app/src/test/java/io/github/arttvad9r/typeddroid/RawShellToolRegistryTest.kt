package io.github.arttvad9r.typeddroid

import io.github.arttvad9r.typeddroid.protocol.CommandRequestPayload
import io.github.arttvad9r.typeddroid.security.AgentTrustMode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawShellToolRegistryTest {
    @Test
    fun `safe mode rejects raw shell before backend`() = runBlocking {
        val backend = RecordingShellBackend()
        val result = registry(backend, AgentTrustMode.SAFE).execute(shellRequest("id"))

        assertFalse(result.ok)
        assertEquals("policy_denied", result.error?.code)
        assertEquals(0, backend.calls)
    }

    @Test
    fun `trusted mode executes raw shell without per command approval`() = runBlocking {
        val backend = RecordingShellBackend()
        val result = registry(backend, AgentTrustMode.TRUSTED).execute(shellRequest("id"))

        assertTrue(result.ok)
        assertEquals(1, backend.calls)
        assertEquals("0", result.result?.get("exitCode")?.toString())
        assertTrue(result.result?.get("stdout")?.toString()?.contains("uid=2000") == true)
    }

    @Test
    fun `trusted shell validates timeout before backend`() = runBlocking {
        val backend = RecordingShellBackend()
        val result = registry(backend, AgentTrustMode.TRUSTED).execute(
            CommandRequestPayload(
                tool = BridgeToolRegistry.SHELL_RUN,
                requestId = "shell-timeout",
                arguments = buildJsonObject {
                    put("command", "id")
                    put("timeoutMillis", 300_001)
                },
            )
        )

        assertFalse(result.ok)
        assertEquals("invalid_arguments", result.error?.code)
        assertEquals(0, backend.calls)
    }

    private fun shellRequest(command: String) = CommandRequestPayload(
        tool = BridgeToolRegistry.SHELL_RUN,
        requestId = "shell-request",
        arguments = buildJsonObject { put("command", command) },
    )

    private fun registry(backend: RawShellBackend, mode: AgentTrustMode) = BridgeToolRegistry(
        healthRepository = object : DeviceHealthRepository {
            override fun snapshot() = DeviceHealthSnapshot(null, 1, 1, 1, 1)
        },
        appsRepository = object : InstalledAppsRepository {
            override fun listLaunchableApps() = emptyList<InstalledAppSnapshot>()
        },
        filesRepository = object : SafFilesRepository {
            override fun list(pathSegments: List<String>) = SafDirectorySnapshot("root", pathSegments, emptyList())
        },
        rawShellBackend = backend,
        agentTrustMode = { mode },
    )

    private class RecordingShellBackend : RawShellBackend {
        var calls = 0
        override fun readiness() = PrivilegedBackendReadiness(ready = true)
        override suspend fun execute(command: String, timeoutMillis: Long): RawShellExecutionResult {
            calls += 1
            return RawShellExecutionResult(
                ok = true,
                exitCode = 0,
                stdout = "uid=2000(shell)".encodeToByteArray(),
                stderr = ByteArray(0),
            )
        }
    }
}
