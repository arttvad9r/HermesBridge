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
    fun routesPermissionRevokeToSpecializedHandler() = runBlocking {
        val permissions = object : AppPermissionsRepository {
            override fun read(packageName: String) = AppPermissionsSnapshot(
                packageName = packageName,
                permissions = listOf(
                    AppPermissionSnapshot(
                        name = "android.permission.INTERNET",
                        granted = true,
                        protection = "normal",
                        dangerous = false,
                        group = null,
                        implicit = false,
                        neverForLocation = false,
                    )
                ),
            )
        }
        val router = router(permissions)

        val result = router.execute(
            CommandRequestPayload(
                tool = AppPermissionRevokeToolHandler.TOOL_NAME,
                requestId = "router-revoke",
                arguments = buildJsonObject {
                    put("packageName", "com.example.visible")
                    put("permissionName", "android.permission.INTERNET")
                },
            )
        )

        assertFalse(result.ok)
        assertEquals("permission_not_dangerous", result.error?.code)
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

    @Test
    fun invalidRequestIdFailsClosedThroughReplayGuard() = runBlocking {
        val router = router(null)
        val result = router.execute(
            CommandRequestPayload(
                tool = BridgeToolRegistry.DEVICE_HEALTH,
                requestId = "invalid request id",
            )
        )

        assertFalse(result.ok)
        assertEquals("invalid_request_id", result.error?.code)
    }

    @Test
    fun localRepositoryExceptionTextDoesNotCrossRouterBoundary() = runBlocking {
        val marker = "SECRET_MARKER:/data/user/0/io.github.arttvad9r.hermesbridge/private"
        val permissions = object : AppPermissionsRepository {
            override fun read(packageName: String): AppPermissionsSnapshot {
                throw IllegalStateException(marker)
            }
        }
        val router = router(permissions)

        val result = router.execute(
            CommandRequestPayload(
                tool = AppPermissionsToolHandler.TOOL_NAME,
                requestId = "router-redaction",
                arguments = buildJsonObject { put("packageName", "com.example.visible") },
            )
        )

        assertFalse(result.ok)
        assertEquals("app_permissions_failed", result.error?.code)
        assertEquals("Android could not read app permission metadata.", result.error?.message)
        assertFalse(result.error?.message.orEmpty().contains(marker))
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
