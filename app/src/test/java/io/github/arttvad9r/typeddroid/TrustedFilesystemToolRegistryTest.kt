package io.github.arttvad9r.typeddroid

import io.github.arttvad9r.typeddroid.protocol.CommandRequestPayload
import io.github.arttvad9r.typeddroid.security.AgentTrustMode
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedFilesystemToolRegistryTest {
    @Test
    fun `safe mode rejects shell filesystem before backend`() = runBlocking {
        val shell = RecordingShellBackend()
        val result = registry(shell, AgentTrustMode.SAFE).execute(request(BridgeToolRegistry.FS_READ) {
            put("path", "/data/local/tmp/example.txt")
        })
        assertFalse(result.ok)
        assertEquals("policy_denied", result.error?.code)
        assertTrue(shell.commands.isEmpty())
    }

    @Test
    fun `stat uses Android toybox compatible single-line format`() = runBlocking {
        val shell = RecordingShellBackend(stdout = "regular file|3|123\n")
        val result = registry(shell, AgentTrustMode.TRUSTED).execute(request(BridgeToolRegistry.FS_STAT) {
            put("path", "/data/local/tmp/a file.txt")
        })
        assertTrue(result.ok)
        assertTrue(shell.commands.single().contains("'%F|%s|%Y'"))
        assertEquals("3", result.result?.get("sizeBytes")?.toString())
    }

    @Test
    fun `write quotes path and canonicalizes base64`() = runBlocking {
        val shell = RecordingShellBackend()
        val payload = Base64.getEncoder().encodeToString("hello".encodeToByteArray())
        val result = registry(shell, AgentTrustMode.TRUSTED).execute(request(BridgeToolRegistry.FS_WRITE) {
            put("path", "/data/local/tmp/a'; echo injected")
            put("dataBase64", payload)
            put("truncate", true)
        })
        assertTrue(result.ok)
        val command = shell.commands.single()
        assertTrue(command.contains("a'\"'\"'; echo injected'"))
        assertTrue(command.contains("base64 -d"))
    }

    @Test
    fun `typed mutation rejects paths outside mutable roots`() = runBlocking {
        val shell = RecordingShellBackend()
        val result = registry(shell, AgentTrustMode.TRUSTED).execute(request(BridgeToolRegistry.FS_WRITE) {
            put("path", "/system/should-not-write")
            put("dataBase64", "")
        })
        assertFalse(result.ok)
        assertEquals("invalid_arguments", result.error?.code)
        assertTrue(shell.commands.isEmpty())
    }

    @Test
    fun `delete and move protect mutation roots themselves`() = runBlocking {
        val shell = RecordingShellBackend()
        val registry = registry(shell, AgentTrustMode.TRUSTED)
        val delete = registry.execute(request(BridgeToolRegistry.FS_DELETE) {
            put("path", "/data/local/tmp")
            put("recursive", true)
        })
        val move = registry.execute(request(BridgeToolRegistry.FS_MOVE) {
            put("sourcePath", "/sdcard")
            put("destinationPath", "/sdcard/moved")
        })
        assertEquals("protected_path", delete.error?.code)
        assertEquals("protected_path", move.error?.code)
        assertTrue(shell.commands.isEmpty())
    }

    private fun request(tool: String, content: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        CommandRequestPayload(tool = tool, requestId = tool, arguments = buildJsonObject(content))

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

    private class RecordingShellBackend(private val stdout: String = "") : RawShellBackend {
        val commands = mutableListOf<String>()
        override fun readiness() = PrivilegedBackendReadiness(ready = true)
        override suspend fun execute(command: String, timeoutMillis: Long): RawShellExecutionResult {
            commands += command
            return RawShellExecutionResult(ok = true, exitCode = 0, stdout = stdout.encodeToByteArray())
        }
    }
}
