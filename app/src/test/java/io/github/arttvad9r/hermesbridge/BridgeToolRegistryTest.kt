package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.protocol.CommandRequestPayload
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeToolRegistryTest {
    private val healthRepository = object : DeviceHealthRepository {
        override fun snapshot() = DeviceHealthSnapshot(
            batteryPercent = 73,
            availableMemoryBytes = 10L,
            totalMemoryBytes = 20L,
            availableStorageBytes = 30L,
            totalStorageBytes = 40L,
        )
    }

    private val appsRepository = object : InstalledAppsRepository {
        override fun listLaunchableApps() = listOf(
            InstalledAppSnapshot(
                packageName = "com.example.app",
                label = "Example",
                versionName = "1.2.3",
                versionCode = 12L,
                systemApp = false,
                enabled = true,
            )
        )
    }

    private val filesRepository = object : SafFilesRepository {
        override fun list(pathSegments: List<String>) = SafDirectorySnapshot(
            rootName = "Shared",
            pathSegments = pathSegments,
            entries = listOf(
                SafFileEntrySnapshot(
                    name = "notes.txt",
                    pathSegments = pathSegments + "notes.txt",
                    directory = false,
                    mimeType = "text/plain",
                    sizeBytes = 123L,
                    lastModifiedEpochMillis = 456L,
                )
            ),
        )
    }

    private val registry = BridgeToolRegistry(
        healthRepository = healthRepository,
        appsRepository = appsRepository,
        filesRepository = filesRepository,
    )

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
    fun appsListExecutesWithoutApproval() = runBlocking {
        val result = registry.execute(
            CommandRequestPayload(
                tool = BridgeToolRegistry.APPS_LIST,
                requestId = "request-apps",
            )
        )

        assertTrue(result.ok)
        assertEquals("1", result.result?.get("count")?.toString())
        assertTrue(result.result?.get("apps")?.toString()?.contains("com.example.app") == true)
    }

    @Test
    fun appsListRejectsArguments() = runBlocking {
        val result = registry.execute(
            CommandRequestPayload(
                tool = BridgeToolRegistry.APPS_LIST,
                requestId = "request-apps-args",
                arguments = buildJsonObject { put("unexpected", true) },
            )
        )

        assertFalse(result.ok)
        assertEquals("invalid_arguments", result.error?.code)
    }

    @Test
    fun filesListExecutesForSafePathSegments() = runBlocking {
        val result = registry.execute(
            CommandRequestPayload(
                tool = BridgeToolRegistry.FILES_LIST,
                requestId = "request-files",
                arguments = buildJsonObject {
                    put(
                        "pathSegments",
                        buildJsonArray {
                            add(JsonPrimitive("Documents"))
                            add(JsonPrimitive("Notes"))
                        },
                    )
                },
            )
        )

        assertTrue(result.ok)
        assertEquals("1", result.result?.get("count")?.toString())
        assertTrue(result.result?.toString()?.contains("notes.txt") == true)
    }

    @Test
    fun filesListRejectsTraversalSegments() = runBlocking {
        val result = registry.execute(
            CommandRequestPayload(
                tool = BridgeToolRegistry.FILES_LIST,
                requestId = "request-files-traversal",
                arguments = buildJsonObject {
                    put(
                        "pathSegments",
                        buildJsonArray {
                            add(JsonPrimitive(".."))
                        },
                    )
                },
            )
        )

        assertFalse(result.ok)
        assertEquals("invalid_arguments", result.error?.code)
    }

    @Test
    fun filesListRejectsUnexpectedArguments() = runBlocking {
        val result = registry.execute(
            CommandRequestPayload(
                tool = BridgeToolRegistry.FILES_LIST,
                requestId = "request-files-extra",
                arguments = buildJsonObject { put("path", "../") },
            )
        )

        assertFalse(result.ok)
        assertEquals("invalid_arguments", result.error?.code)
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
