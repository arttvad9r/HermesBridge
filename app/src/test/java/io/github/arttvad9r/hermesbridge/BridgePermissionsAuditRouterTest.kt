package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.protocol.CommandRequestPayload
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgePermissionsAuditRouterTest {
    @Test
    fun routesPermissionsAuditWithoutBroadeningAppSource() = runBlocking {
        val appsRepository = object : InstalledAppsRepository {
            override fun listLaunchableApps() = listOf(
                InstalledAppSnapshot(
                    packageName = "com.example.visible",
                    label = "Visible",
                    versionName = "1.0",
                    versionCode = 1,
                    systemApp = false,
                    enabled = true,
                )
            )
        }
        val permissionsRepository = object : AppPermissionsRepository {
            override fun read(packageName: String) = AppPermissionsSnapshot(
                packageName,
                listOf(
                    AppPermissionSnapshot(
                        name = "android.permission.CAMERA",
                        granted = true,
                        protection = "dangerous",
                        dangerous = true,
                        group = null,
                        implicit = false,
                        neverForLocation = false,
                    )
                ),
            )
        }
        val router = BridgeCommandRouter(
            coreRegistry = BridgeToolRegistry(
                healthRepository = object : DeviceHealthRepository {
                    override fun snapshot() = DeviceHealthSnapshot(null, 1, 2, 3, 4)
                },
                appsRepository = appsRepository,
                filesRepository = object : SafFilesRepository {
                    override fun list(pathSegments: List<String>) = SafDirectorySnapshot(
                        rootName = "Shared",
                        pathSegments = pathSegments,
                        entries = emptyList(),
                    )
                },
            ),
            appsRepository = appsRepository,
            appPermissionsRepository = permissionsRepository,
        )

        val result = router.execute(
            CommandRequestPayload(
                tool = AppPermissionsAuditToolHandler.TOOL_NAME,
                requestId = "audit-router",
            )
        )

        assertTrue(result.ok)
        assertEquals("1", result.result?.get("visibleAppCount")?.toString())
        assertEquals("1", result.result?.get("matchedAppCount")?.toString())
    }
}
