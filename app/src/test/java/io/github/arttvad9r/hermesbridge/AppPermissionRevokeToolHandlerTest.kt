package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.protocol.CommandRequestPayload
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPermissionRevokeToolHandlerTest {
    @Test
    fun dangerousGrantedPermissionRequiresExactApprovalBeforeBackendExecution() = runBlocking {
        BridgeApprovalRuntime.clear()
        val backend = RecordingBackend()
        val handler = handler(backend = backend)
        val request = request("request-1", CAMERA)

        val first = handler.execute(request)
        assertFalse(first.ok)
        assertEquals("approval_required", first.error?.code)
        assertEquals(0, backend.revokeCalls)

        val ticket = BridgeApprovalRuntime.pendingTickets.value.single()
        BridgeApprovalRuntime.approve(ticket.id)

        val second = handler.execute(request.copy(requestId = "request-2"))
        assertTrue(second.ok)
        assertEquals(1, backend.revokeCalls)
        assertEquals(APP_PACKAGE, backend.packageName)
        assertEquals(CAMERA, backend.permissionName)
        assertEquals(10, backend.userId)
        assertTrue(BridgeApprovalRuntime.pendingTickets.value.isEmpty())
    }

    @Test
    fun approvedPermissionRevokeIsSingleUse() = runBlocking {
        BridgeApprovalRuntime.clear()
        val backend = RecordingBackend()
        val handler = handler(backend = backend)
        val request = request("request-1", CAMERA)

        handler.execute(request)
        BridgeApprovalRuntime.approve(BridgeApprovalRuntime.pendingTickets.value.single().id)
        assertTrue(handler.execute(request.copy(requestId = "request-2")).ok)

        val replay = handler.execute(request.copy(requestId = "request-3"))
        assertFalse(replay.ok)
        assertEquals("approval_required", replay.error?.code)
        assertEquals(1, backend.revokeCalls)
    }

    @Test
    fun nonDangerousPermissionIsRejectedBeforeApprovalOrBackend() = runBlocking {
        BridgeApprovalRuntime.clear()
        val backend = RecordingBackend()
        val handler = handler(
            backend = backend,
            permissions = listOf(permission(INTERNET, dangerous = false, granted = true)),
        )

        val result = handler.execute(request("request-normal", INTERNET))

        assertFalse(result.ok)
        assertEquals("permission_not_dangerous", result.error?.code)
        assertEquals(0, backend.revokeCalls)
        assertTrue(BridgeApprovalRuntime.pendingTickets.value.isEmpty())
    }

    @Test
    fun alreadyRevokedPermissionIsIdempotentWithoutApproval() = runBlocking {
        BridgeApprovalRuntime.clear()
        val backend = RecordingBackend()
        val handler = handler(
            backend = backend,
            permissions = listOf(permission(CAMERA, dangerous = true, granted = false)),
        )

        val result = handler.execute(request("request-already", CAMERA))

        assertTrue(result.ok)
        assertEquals("true", result.result?.get("alreadyRevoked")?.toString())
        assertEquals(0, backend.revokeCalls)
        assertTrue(BridgeApprovalRuntime.pendingTickets.value.isEmpty())
    }

    @Test
    fun invisiblePackageIsRejectedBeforePermissionMetadataRead() = runBlocking {
        BridgeApprovalRuntime.clear()
        val backend = RecordingBackend()
        var permissionReads = 0
        val handler = AppPermissionRevokeToolHandler(
            appsRepository = object : InstalledAppsRepository {
                override fun listLaunchableApps() = emptyList<InstalledAppSnapshot>()
            },
            permissionsRepository = object : AppPermissionsRepository {
                override fun read(packageName: String): AppPermissionsSnapshot {
                    permissionReads += 1
                    return AppPermissionsSnapshot(packageName, emptyList())
                }
            },
            privilegedBackend = backend,
            userIdProvider = { 10 },
        )

        val result = handler.execute(request("request-hidden", CAMERA))

        assertFalse(result.ok)
        assertEquals("app_not_visible", result.error?.code)
        assertEquals(0, permissionReads)
        assertEquals(0, backend.revokeCalls)
    }

    @Test
    fun bridgePackageIsProtectedBeforeVisibilityLookup() = runBlocking {
        BridgeApprovalRuntime.clear()
        val backend = RecordingBackend()
        val handler = handler(backend = backend)
        val result = handler.execute(
            CommandRequestPayload(
                tool = AppPermissionRevokeToolHandler.TOOL_NAME,
                requestId = "request-self",
                arguments = buildJsonObject {
                    put("packageName", "io.github.arttvad9r.hermesbridge")
                    put("permissionName", CAMERA)
                },
            )
        )

        assertFalse(result.ok)
        assertEquals("protected_package", result.error?.code)
        assertEquals(0, backend.revokeCalls)
        assertTrue(BridgeApprovalRuntime.pendingTickets.value.isEmpty())
    }

    @Test
    fun pmRevokeCommandHasNoAgentControlledFlags() {
        assertArrayEquals(
            arrayOf("pm", "revoke", "--user", "10", APP_PACKAGE, CAMERA),
            buildPmRevokePermissionCommand(APP_PACKAGE, CAMERA, 10),
        )
        assertTrue(runCatching { buildPmRevokePermissionCommand("../bad", CAMERA, 10) }.isFailure)
        assertTrue(runCatching { buildPmRevokePermissionCommand(APP_PACKAGE, "../bad", 10) }.isFailure)
        assertTrue(runCatching { buildPmRevokePermissionCommand(APP_PACKAGE, CAMERA, -1) }.isFailure)
    }

    private fun handler(
        backend: RecordingBackend,
        permissions: List<AppPermissionSnapshot> = listOf(permission(CAMERA, dangerous = true, granted = true)),
    ) = AppPermissionRevokeToolHandler(
        appsRepository = object : InstalledAppsRepository {
            override fun listLaunchableApps() = listOf(
                InstalledAppSnapshot(
                    packageName = APP_PACKAGE,
                    label = "Example",
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
                permissions = permissions,
            )
        },
        privilegedBackend = backend,
        userIdProvider = { 10 },
    )

    private fun request(requestId: String, permissionName: String) = CommandRequestPayload(
        tool = AppPermissionRevokeToolHandler.TOOL_NAME,
        requestId = requestId,
        arguments = buildJsonObject {
            put("packageName", APP_PACKAGE)
            put("permissionName", permissionName)
        },
    )

    private class RecordingBackend : PrivilegedAppsBackend {
        var revokeCalls = 0
        var packageName = ""
        var permissionName = ""
        var userId = -1

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
            this.packageName = packageName
            this.permissionName = permissionName
            this.userId = userId
            return PrivilegedOperationResult(ok = true)
        }
    }

    companion object {
        private const val APP_PACKAGE = "com.example.app"
        private const val CAMERA = "android.permission.CAMERA"
        private const val INTERNET = "android.permission.INTERNET"

        private fun permission(
            name: String,
            dangerous: Boolean,
            granted: Boolean,
        ) = AppPermissionSnapshot(
            name = name,
            granted = granted,
            protection = if (dangerous) "dangerous" else "normal",
            dangerous = dangerous,
            group = null,
            implicit = false,
            neverForLocation = false,
        )
    }
}
