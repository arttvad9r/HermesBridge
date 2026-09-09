package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.protocol.CommandRequestPayload
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPermissionsAuditToolHandlerTest {
    @Test
    fun returnsOnlyGrantedDangerousPermissionsFromVisibleApps() {
        val apps = listOf(
            app("com.example.camera", "Camera"),
            app("com.example.notes", "Notes"),
        )
        val repository = object : AppPermissionsRepository {
            override fun read(packageName: String): AppPermissionsSnapshot = when (packageName) {
                "com.example.camera" -> AppPermissionsSnapshot(
                    packageName,
                    listOf(
                        permission("android.permission.CAMERA", granted = true, dangerous = true),
                        permission("android.permission.RECORD_AUDIO", granted = false, dangerous = true),
                        permission("android.permission.INTERNET", granted = true, dangerous = false),
                    ),
                )
                else -> AppPermissionsSnapshot(
                    packageName,
                    listOf(permission("android.permission.INTERNET", granted = true, dangerous = false)),
                )
            }
        }

        val result = handler(apps, repository).execute(request())

        assertTrue(result.ok)
        assertEquals("2", result.result?.get("visibleAppCount")?.toString())
        assertEquals("2", result.result?.get("scannedAppCount")?.toString())
        assertEquals("1", result.result?.get("matchedAppCount")?.toString())
        assertEquals("1", result.result?.get("dangerousGrantedPermissionCount")?.toString())
        val serialized = result.result.toString()
        assertTrue(serialized.contains("com.example.camera"))
        assertTrue(serialized.contains("android.permission.CAMERA"))
        assertFalse(serialized.contains("android.permission.RECORD_AUDIO"))
        assertFalse(serialized.contains("android.permission.INTERNET"))
    }

    @Test
    fun capsScannedAppsAndReportsTruncation() {
        val apps = (0..AppPermissionsAuditToolHandler.MAX_SCANNED_APPS).map { index ->
            app("com.example.app$index", "App $index")
        }
        var reads = 0
        val repository = object : AppPermissionsRepository {
            override fun read(packageName: String): AppPermissionsSnapshot {
                reads += 1
                return AppPermissionsSnapshot(packageName, emptyList())
            }
        }

        val result = handler(apps, repository).execute(request())

        assertTrue(result.ok)
        assertEquals(AppPermissionsAuditToolHandler.MAX_SCANNED_APPS, reads)
        assertEquals("true", result.result?.get("scanTruncated")?.toString())
    }

    @Test
    fun countsSkippedAppsWithoutFailingWholeAudit() {
        val apps = listOf(
            app("com.example.good", "Good"),
            app("com.example.gone", "Gone"),
        )
        val repository = object : AppPermissionsRepository {
            override fun read(packageName: String): AppPermissionsSnapshot {
                if (packageName.endsWith("gone")) throw AppPermissionsUnavailableException("gone")
                return AppPermissionsSnapshot(
                    packageName,
                    listOf(permission("android.permission.CAMERA", granted = true, dangerous = true)),
                )
            }
        }

        val result = handler(apps, repository).execute(request())

        assertTrue(result.ok)
        assertEquals("1", result.result?.get("skippedAppCount")?.toString())
        assertEquals("1", result.result?.get("matchedAppCount")?.toString())
    }

    @Test
    fun rejectsArgumentsAndMissingRepository() {
        val apps = listOf(app("com.example.app", "App"))
        val withArgument = handler(apps, object : AppPermissionsRepository {
            override fun read(packageName: String) = AppPermissionsSnapshot(packageName, emptyList())
        }).execute(
            CommandRequestPayload(
                tool = AppPermissionsAuditToolHandler.TOOL_NAME,
                requestId = "audit-args",
                arguments = buildJsonObject { put("includeSystem", true) },
            )
        )
        val unavailable = AppPermissionsAuditToolHandler(
            appsRepository = repository(apps),
            permissionsRepository = null,
        ).execute(request("audit-unavailable"))

        assertFalse(withArgument.ok)
        assertEquals("invalid_arguments", withArgument.error?.code)
        assertFalse(unavailable.ok)
        assertEquals("app_permissions_unavailable", unavailable.error?.code)
    }

    private fun handler(apps: List<InstalledAppSnapshot>, permissions: AppPermissionsRepository) =
        AppPermissionsAuditToolHandler(repository(apps), permissions)

    private fun repository(apps: List<InstalledAppSnapshot>) = object : InstalledAppsRepository {
        override fun listLaunchableApps() = apps
    }

    private fun request(requestId: String = "audit-1") = CommandRequestPayload(
        tool = AppPermissionsAuditToolHandler.TOOL_NAME,
        requestId = requestId,
    )

    private fun app(packageName: String, label: String) = InstalledAppSnapshot(
        packageName = packageName,
        label = label,
        versionName = "1.0",
        versionCode = 1L,
        systemApp = false,
        enabled = true,
    )

    private fun permission(name: String, granted: Boolean, dangerous: Boolean) =
        AppPermissionSnapshot(
            name = name,
            granted = granted,
            protection = if (dangerous) "dangerous" else "normal",
            dangerous = dangerous,
            group = null,
            implicit = false,
            neverForLocation = false,
        )
}
