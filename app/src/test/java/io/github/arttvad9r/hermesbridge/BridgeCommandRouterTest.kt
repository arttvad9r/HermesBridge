package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.protocol.CommandRequestPayload
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeCommandRouterTest {
    private val healthRepository = object : DeviceHealthRepository {
        override fun snapshot() = DeviceHealthSnapshot(null, 1, 2, 3, 4)
    }

    private val appsRepository = object : InstalledAppsRepository {
        override fun listLaunchableApps() = listOf(
            InstalledAppSnapshot(
                packageName = "com.example.visible",
                label = "Visible",
                versionName = null,
                versionCode = 1,
                systemApp = false,
                enabled = true,
            )
        )
    }

    private val filesRepository = object : SafFilesRepository {
        override fun list(pathSegments: List<String>) = SafDirectorySnapshot(
            rootName = "Shared",
            pathSegments = pathSegments,
            entries = emptyList(),
        )
    }

    @Test
    fun routesAppPermissionsToSpecializedHandler() = runBlocking {
        val permissions = object : AppPermissionsRepository {
            override fun read(packageName: String) = AppPermissionsSnapshot(
                packageName = packageName,
                permissions = emptyList(),
            )
        }
        val router = router(permissions)

        val result = router.execute(
            CommandRequestPayload(
                tool = AppPermissionsToolHandler.TOOL_NAME,
                requestId = "router-permissions",
                arguments = buildJsonObject { put("packageName", "com.example.visible") },
            )
        )

        assertTrue(result.ok)
        assertEquals("0", result.result?.get("permissionCount")?.toString())
    }

    @Test
    fun unknownToolsStillFailClosedThroughCoreRegistry() = runBlocking {
        val router = router(null)
        val result = router.execute(
            CommandRequestPayload(
                tool = "shell.run",
                requestId = "router-unknown",
            )
        )

        assertFalse(result.ok)
        assertEquals("unknown_tool", result.error?.code)
    }

    private fun router(permissions: AppPermissionsRepository?) = BridgeCommandRouter(
        coreRegistry = BridgeToolRegistry(
            healthRepository = healthRepository,
            appsRepository = appsRepository,
            filesRepository = filesRepository,
        ),
        appsRepository = appsRepository,
        appPermissionsRepository = permissions,
    )
}
