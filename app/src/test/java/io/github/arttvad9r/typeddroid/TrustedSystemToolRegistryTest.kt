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

class TrustedSystemToolRegistryTest {
    @Test
    fun `safe mode rejects typed system control before shell`() = runBlocking {
        val shell = RecordingShellBackend()
        val result = registry(shell, AgentTrustMode.SAFE).execute(
            request(BridgeToolRegistry.SETTINGS_GET) {
                put("namespace", "global")
                put("key", "airplane_mode_on")
            },
        )

        assertFalse(result.ok)
        assertEquals("policy_denied", result.error?.code)
        assertTrue(shell.commands.isEmpty())
    }

    @Test
    fun `trusted settings tools quote values as single argv items`() = runBlocking {
        val shell = RecordingShellBackend()
        val registry = registry(shell, AgentTrustMode.TRUSTED)

        val get = registry.execute(request(BridgeToolRegistry.SETTINGS_GET) {
            put("namespace", "global")
            put("key", "typed_droid_test")
        })
        val put = registry.execute(request(BridgeToolRegistry.SETTINGS_PUT) {
            put("namespace", "global")
            put("key", "typed_droid_test")
            put("value", "a'; echo injected")
        })

        assertTrue(get.ok)
        assertTrue(put.ok)
        assertEquals("'settings' 'get' 'global' 'typed_droid_test'", shell.commands[0])
        assertEquals("'settings' 'put' 'global' 'typed_droid_test' 'a'\"'\"'; echo injected'", shell.commands[1])
    }

    @Test
    fun `trusted process list is returned as structured rows`() = runBlocking {
        val shell = RecordingShellBackend(
            stdout = "USER PID PPID NAME\nroot 1 0 init\nshell 2000 1 sh\n",
        )
        val result = registry(shell, AgentTrustMode.TRUSTED).execute(
            CommandRequestPayload(
                tool = BridgeToolRegistry.PROCESS_LIST,
                requestId = "processes",
                arguments = buildJsonObject {},
            ),
        )

        assertTrue(result.ok)
        assertTrue(result.result?.get("processes")?.toString()?.contains("init") == true)
        assertTrue(result.result?.get("processes")?.toString()?.contains("2000") == true)
    }

    @Test
    fun `appops set protects TypedDroid itself`() = runBlocking {
        val shell = RecordingShellBackend()
        val result = registry(shell, AgentTrustMode.TRUSTED).execute(request(BridgeToolRegistry.APPOPS_SET) {
            put("packageName", "io.github.arttvad9r.typeddroid")
            put("opName", "CAMERA")
            put("mode", "ignore")
        })

        assertFalse(result.ok)
        assertEquals("protected_package", result.error?.code)
        assertTrue(shell.commands.isEmpty())
    }

    @Test
    fun `capabilities reveal trust and backend readiness without executing shell`() = runBlocking {
        val shell = RecordingShellBackend()
        val result = registry(shell, AgentTrustMode.TRUSTED).execute(
            CommandRequestPayload(
                tool = BridgeToolRegistry.DEVICE_CAPABILITIES,
                requestId = "capabilities",
                arguments = buildJsonObject {},
            ),
        )

        assertTrue(result.ok)
        assertEquals("\"trusted\"", result.result?.get("agentTrustMode")?.toString())
        assertEquals("true", result.result?.get("rawShell")?.toString())
        assertTrue(shell.commands.isEmpty())
    }

    private fun request(
        tool: String,
        content: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ) = CommandRequestPayload(tool = tool, requestId = tool, arguments = buildJsonObject(content))

    private fun registry(shell: RawShellBackend, mode: AgentTrustMode) = BridgeToolRegistry(
        healthRepository = object : DeviceHealthRepository {
            override fun snapshot() = DeviceHealthSnapshot(null, 1, 1, 1, 1)
        },
        appsRepository = object : InstalledAppsRepository {
            override fun listLaunchableApps() = emptyList<InstalledAppSnapshot>()
        },
        filesRepository = object : SafFilesRepository {
            override fun list(pathSegments: List<String>) = SafDirectorySnapshot("root", pathSegments, emptyList())
        },
        rawShellBackend = shell,
        agentTrustMode = { mode },
    )

    private class RecordingShellBackend(
        private val stdout: String = "value\n",
    ) : RawShellBackend {
        val commands = mutableListOf<String>()
        override fun readiness() = PrivilegedBackendReadiness(ready = true)
        override suspend fun execute(command: String, timeoutMillis: Long): RawShellExecutionResult {
            commands += command
            return RawShellExecutionResult(
                ok = true,
                exitCode = 0,
                stdout = stdout.encodeToByteArray(),
            )
        }
    }
}
