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

class FileTransferToolRegistryTest {
    @Test
    fun `safe mode rejects transfer before backend`() = runBlocking {
        val backend = RecordingTransferBackend()
        val result = registry(backend, AgentTrustMode.SAFE).execute(pushRequest())
        assertFalse(result.ok)
        assertEquals("policy_denied", result.error?.code)
        assertTrue(backend.pushes.isEmpty())
    }

    @Test
    fun `trusted push forwards verified descriptor and destination`() = runBlocking {
        val backend = RecordingTransferBackend()
        val result = registry(backend, AgentTrustMode.TRUSTED).execute(pushRequest())
        assertTrue(result.ok)
        assertEquals(1, backend.pushes.size)
        val push = backend.pushes.single()
        assertEquals("file_123e4567-e89b-12d3-a456-426614174000", push.descriptor.artifactId)
        assertEquals("/sdcard/Download/hello.bin", push.destinationPath)
        assertTrue(push.overwrite)
        assertTrue(push.createParents)
    }

    @Test
    fun `trusted pull forwards only validated upload slot`() = runBlocking {
        val backend = RecordingTransferBackend()
        val result = registry(backend, AgentTrustMode.TRUSTED).execute(
            CommandRequestPayload(
                tool = BridgeToolRegistry.TRANSFER_PULL,
                requestId = "pull-1",
                arguments = buildJsonObject {
                    put("sourcePath", "/data/local/tmp/source.bin")
                    put("uploadId", "upload_123e4567-e89b-12d3-a456-426614174000")
                    put("uploadToken", "B".repeat(43))
                },
            )
        )
        assertTrue(result.ok)
        assertEquals(Triple("/data/local/tmp/source.bin", "upload_123e4567-e89b-12d3-a456-426614174000", "B".repeat(43)), backend.pulls.single())
    }

    @Test
    fun `push rejects destination outside trusted mutable roots`() = runBlocking {
        val backend = RecordingTransferBackend()
        val bad = pushRequest(destinationPath = "/system/secret.bin")
        val result = registry(backend, AgentTrustMode.TRUSTED).execute(bad)
        assertFalse(result.ok)
        assertEquals("invalid_arguments", result.error?.code)
        assertTrue(backend.pushes.isEmpty())
    }

    private fun pushRequest(destinationPath: String = "/sdcard/Download/hello.bin") = CommandRequestPayload(
        tool = BridgeToolRegistry.TRANSFER_PUSH,
        requestId = "push-1",
        arguments = buildJsonObject {
            put("artifactId", "file_123e4567-e89b-12d3-a456-426614174000")
            put("downloadToken", "A".repeat(43))
            put("fileName", "hello.bin")
            put("sizeBytes", 123L)
            put("sha256", "a".repeat(64))
            put("destinationPath", destinationPath)
            put("overwrite", true)
            put("createParents", true)
        },
    )

    private fun registry(backend: FileTransferBackend, mode: AgentTrustMode) = BridgeToolRegistry(
        healthRepository = object : DeviceHealthRepository {
            override fun snapshot() = DeviceHealthSnapshot(null, 1, 1, 1, 1)
        },
        appsRepository = object : InstalledAppsRepository {
            override fun listLaunchableApps() = emptyList<InstalledAppSnapshot>()
        },
        filesRepository = object : SafFilesRepository {
            override fun list(pathSegments: List<String>) = SafDirectorySnapshot("root", pathSegments, emptyList())
        },
        fileTransferBackend = backend,
        agentTrustMode = { mode },
    )

    private data class Push(
        val descriptor: FileTransferDescriptor,
        val destinationPath: String,
        val overwrite: Boolean,
        val createParents: Boolean,
    )

    private class RecordingTransferBackend : FileTransferBackend {
        val pushes = mutableListOf<Push>()
        val pulls = mutableListOf<Triple<String, String, String>>()
        override fun readiness() = PrivilegedBackendReadiness(true)
        override suspend fun pushToDevice(
            descriptor: FileTransferDescriptor,
            destinationPath: String,
            overwrite: Boolean,
            createParents: Boolean,
        ): FileTransferResult {
            pushes += Push(descriptor, destinationPath, overwrite, createParents)
            return FileTransferResult(true, descriptor.fileName, descriptor.sizeBytes, descriptor.sha256)
        }
        override suspend fun pullFromDevice(sourcePath: String, uploadId: String, uploadToken: String): FileTransferResult {
            pulls += Triple(sourcePath, uploadId, uploadToken)
            return FileTransferResult(true, "source.bin", 42, "b".repeat(64))
        }
    }
}
