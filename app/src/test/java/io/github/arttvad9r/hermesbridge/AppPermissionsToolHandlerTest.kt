package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.protocol.CommandRequestPayload
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPermissionsToolHandlerTest {
    private val appsRepository = object : InstalledAppsRepository {
        override fun listLaunchableApps() = listOf(
            InstalledAppSnapshot(
                packageName = "com.example.visible",
                label = "Visible App",
                versionName = "2.0",
                versionCode = 20L,
                systemApp = false,
                enabled = true,
            )
        )
    }

    @Test
    fun returnsPermissionsOnlyForLauncherVisiblePackage() {
        val repository = RecordingPermissionsRepository()
        val result = handler(repository).execute(
            CommandRequestPayload(
                tool = AppPermissionsToolHandler.TOOL_NAME,
                requestId = "permissions-1",
                arguments = buildJsonObject { put("packageName", "com.example.visible") },
            )
        )

        assertTrue(result.ok)
        assertEquals(1, repository.readCalls)
        assertEquals("com.example.visible", repository.lastPackageName)
        assertEquals("2", result.result?.get("permissionCount")?.toString())
        assertEquals("1", result.result?.get("dangerousGrantedCount")?.toString())
        val serialized = result.result.toString()
        assertTrue(serialized.contains("android.permission.CAMERA"))
        assertTrue(serialized.contains("android.permission.INTERNET"))
    }

    @Test
    fun rejectsPackageOutsideLauncherVisibleSetBeforeMetadataRead() {
        val repository = RecordingPermissionsRepository()
        val result = handler(repository).execute(
            CommandRequestPayload(
                tool = AppPermissionsToolHandler.TOOL_NAME,
                requestId = "permissions-hidden",
                arguments = buildJsonObject { put("packageName", "com.example.hidden") },
            )
        )

        assertFalse(result.ok)
        assertEquals("app_not_visible", result.error?.code)
        assertEquals(0, repository.readCalls)
    }

    @Test
    fun rejectsUnexpectedOrInvalidArguments() {
        val repository = RecordingPermissionsRepository()
        val extra = handler(repository).execute(
            CommandRequestPayload(
                tool = AppPermissionsToolHandler.TOOL_NAME,
                requestId = "permissions-extra",
                arguments = buildJsonObject {
                    put("packageName", "com.example.visible")
                    put("raw", true)
                },
            )
        )
        val invalid = handler(repository).execute(
            CommandRequestPayload(
                tool = AppPermissionsToolHandler.TOOL_NAME,
                requestId = "permissions-invalid",
                arguments = buildJsonObject { put("packageName", "../bad") },
            )
        )

        assertFalse(extra.ok)
        assertEquals("invalid_arguments", extra.error?.code)
        assertFalse(invalid.ok)
        assertEquals("invalid_arguments", invalid.error?.code)
        assertEquals(0, repository.readCalls)
    }

    @Test
    fun failsClosedWhenRepositoryIsUnavailable() {
        val result = AppPermissionsToolHandler(appsRepository, null).execute(
            CommandRequestPayload(
                tool = AppPermissionsToolHandler.TOOL_NAME,
                requestId = "permissions-unavailable",
                arguments = buildJsonObject { put("packageName", "com.example.visible") },
            )
        )

        assertFalse(result.ok)
        assertEquals("app_permissions_unavailable", result.error?.code)
    }

    private fun handler(repository: AppPermissionsRepository) =
        AppPermissionsToolHandler(appsRepository, repository)

    private class RecordingPermissionsRepository : AppPermissionsRepository {
        var readCalls = 0
        var lastPackageName: String? = null

        override fun read(packageName: String): AppPermissionsSnapshot {
            readCalls += 1
            lastPackageName = packageName
            return AppPermissionsSnapshot(
                packageName = packageName,
                permissions = listOf(
                    AppPermissionSnapshot(
                        name = "android.permission.CAMERA",
                        granted = true,
                        protection = "dangerous",
                        dangerous = true,
                        group = "android.permission-group.CAMERA",
                        implicit = false,
                        neverForLocation = false,
                    ),
                    AppPermissionSnapshot(
                        name = "android.permission.INTERNET",
                        granted = true,
                        protection = "normal",
                        dangerous = false,
                        group = null,
                        implicit = false,
                        neverForLocation = false,
                    ),
                ),
            )
        }
    }
}
