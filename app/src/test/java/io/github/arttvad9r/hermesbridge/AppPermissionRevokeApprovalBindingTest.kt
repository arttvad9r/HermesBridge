package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.protocol.CommandRequestPayload
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPermissionRevokeApprovalBindingTest {
    @After
    fun tearDown() {
        BridgeApprovalRuntime.clear()
    }

    @Test
    fun changedPermissionCannotConsumeCameraApproval() = runBlocking {
        BridgeApprovalRuntime.clear()
        val backend = RecordingBackend()
        val handler = AppPermissionRevokeToolHandler(
            appsRepository = object : InstalledAppsRepository {
                override fun listLaunchableApps() = listOf(
                    InstalledAppSnapshot(
                        packageName = PACKAGE_NAME,
                        label = "Fixture",
                        versionName = "1.0",
                        versionCode = 1L,
                        systemApp = false,
                        enabled = true,
                    )
                )
            },
            permissionsRepository = object : AppPermissionsRepository {
                override fun read(packageName: String) = AppPermissionsSnapshot(
                    packageName = packageName,
                    permissions = listOf(
                        dangerousPermission(CAMERA),
                        dangerousPermission(RECORD_AUDIO),
                    ),
                )
            },
            privilegedBackend = backend,
            userIdProvider = { 0 },
        )

        val camera = request("camera-initial", CAMERA)
        val first = handler.execute(camera)
        assertFalse(first.ok)
        assertEquals("approval_required", first.error?.code)
        BridgeApprovalRuntime.approve(BridgeApprovalRuntime.pendingTickets.value.single().id)

        val changed = handler.execute(request("audio-changed", RECORD_AUDIO))
        assertFalse(changed.ok)
        assertEquals("approval_required", changed.error?.code)
        assertEquals(0, backend.revokeCalls)

        val approvedOriginal = handler.execute(camera.copy(requestId = "camera-approved"))
        assertTrue(approvedOriginal.ok)
        assertEquals(1, backend.revokeCalls)
        assertEquals(CAMERA, backend.lastPermission)
    }

    private fun request(requestId: String, permission: String) = CommandRequestPayload(
        tool = AppPermissionRevokeToolHandler.TOOL_NAME,
        requestId = requestId,
        arguments = buildJsonObject {
            put("packageName", PACKAGE_NAME)
            put("permissionName", permission)
        },
    )

    private fun dangerousPermission(name: String) = AppPermissionSnapshot(
        name = name,
        granted = true,
        protection = "dangerous",
        dangerous = true,
        group = null,
        implicit = false,
        neverForLocation = false,
    )

    private class RecordingBackend : PrivilegedAppsBackend {
        var revokeCalls = 0
        var lastPermission = ""

        override fun readiness() = PrivilegedBackendReadiness(ready = true)

        override suspend fun install(
            artifact: VerifiedApkArtifact,
            replace: Boolean,
        ) = PrivilegedOperationResult(ok = false)

        override suspend fun uninstall(
            packageName: String,
            keepData: Boolean,
        ) = PrivilegedOperationResult(ok = false)

        override suspend fun forceStop(packageName: String) = PrivilegedOperationResult(ok = false)

        override suspend fun revokePermission(
            packageName: String,
            permissionName: String,
            userId: Int,
        ): PrivilegedOperationResult {
            revokeCalls += 1
            lastPermission = permissionName
            return PrivilegedOperationResult(ok = true)
        }
    }

    private companion object {
        const val PACKAGE_NAME = "io.github.arttvad9r.hermesbridge.fixture"
        const val CAMERA = "android.permission.CAMERA"
        const val RECORD_AUDIO = "android.permission.RECORD_AUDIO"
    }
}
