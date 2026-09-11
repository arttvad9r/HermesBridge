package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.protocol.CommandRequestPayload
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UiCaptureRemoteBoundaryTest {
    private val healthRepository = object : DeviceHealthRepository {
        override fun snapshot() = DeviceHealthSnapshot(
            batteryPercent = null,
            availableMemoryBytes = 1L,
            totalMemoryBytes = 2L,
            availableStorageBytes = 3L,
            totalStorageBytes = 4L,
        )
    }

    private val appsRepository = object : InstalledAppsRepository {
        override fun listLaunchableApps() = emptyList<InstalledAppSnapshot>()
    }

    private val filesRepository = object : SafFilesRepository {
        override fun list(pathSegments: List<String>) = SafDirectorySnapshot(
            rootName = "Shared",
            pathSegments = pathSegments,
            entries = emptyList(),
        )
    }

    private fun registry() = BridgeToolRegistry(
        healthRepository = healthRepository,
        appsRepository = appsRepository,
        filesRepository = filesRepository,
    )

    @Test
    fun `remote screenshot and capture tools remain unregistered`() = runBlocking {
        val registry = registry()
        val forbiddenRemoteTools = listOf(
            "screen.capture",
            "screen.screenshot",
            "ui.capture",
            "ui.screenshot",
        )

        forbiddenRemoteTools.forEachIndexed { index, tool ->
            val result = registry.execute(
                CommandRequestPayload(
                    tool = tool,
                    requestId = "capture-boundary-$index",
                )
            )

            assertFalse("$tool must not be registered in the standard remote tool surface", result.ok)
            assertEquals("unknown_tool", result.error?.code)
        }
    }
}
