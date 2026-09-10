package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.protocol.BridgeProtocol
import io.github.arttvad9r.hermesbridge.protocol.CommandRequestPayload
import io.github.arttvad9r.hermesbridge.protocol.MessageType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPermissionsResultBoundsTest {
    @Test
    fun permissionProjectionBoundsOnlyRemoteDisplayData() {
        val permissions = (0 until 150).map(::largePermission)
        val projection = projectPermissionsForRemoteResult(permissions)

        assertEquals(150, projection.totalPermissionCount)
        assertEquals(MAX_REMOTE_PERMISSION_ENTRIES, projection.permissions.size)
        assertTrue(projection.truncated)
        assertEquals(permissions.first().name, projection.permissions.first().name)
        projection.permissions.forEach { permission ->
            assertTrue(permission.name.length <= MAX_ANDROID_QUALIFIED_NAME_CHARS)
            assertTrue(permission.group.orEmpty().length <= MAX_REMOTE_PERMISSION_GROUP_CHARS)
            assertTrue(permission.protection.length <= MAX_REMOTE_PERMISSION_PROTECTION_CHARS)
            assertFalse(permission.group.orEmpty().any(Char::isISOControl))
            assertFalse(permission.protection.any(Char::isISOControl))
        }
    }

    @Test
    fun routerReturnsUsefulBoundedPermissionsInsteadOfGenericSizeFailure() = runBlocking {
        val packageName = "com.example.permissions"
        val appsRepository = object : InstalledAppsRepository {
            override fun listLaunchableApps() = listOf(
                InstalledAppSnapshot(
                    packageName = packageName,
                    label = "\u0800".repeat(300) + "\nprivate-label",
                    versionName = "\u0800".repeat(200) + "\u0000private-version",
                    versionCode = Long.MAX_VALUE,
                    systemApp = false,
                    enabled = true,
                )
            )
        }
        val permissionsRepository = object : AppPermissionsRepository {
            override fun read(packageName: String) = AppPermissionsSnapshot(
                packageName = packageName,
                permissions = (0 until AndroidAppPermissionsRepository.MAX_PERMISSIONS)
                    .map(::largePermission),
            )
        }
        val router = BridgeCommandRouter(
            coreRegistry = BridgeToolRegistry(
                healthRepository = object : DeviceHealthRepository {
                    override fun snapshot() = DeviceHealthSnapshot(null, 1L, 2L, 3L, 4L)
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
                tool = AppPermissionsToolHandler.TOOL_NAME,
                requestId = "permissions-result-bounds",
                arguments = buildJsonObject { put("packageName", packageName) },
            )
        )

        assertTrue(result.ok)
        assertEquals(
            AndroidAppPermissionsRepository.MAX_PERMISSIONS.toString(),
            result.result?.get("permissionCount")?.toString(),
        )
        assertEquals(
            MAX_REMOTE_PERMISSION_ENTRIES.toString(),
            result.result?.get("returnedPermissionCount")?.toString(),
        )
        assertEquals("true", result.result?.get("permissionsTruncated")?.toString())

        val payloadBytes = BridgeProtocol.json.encodeToString(
            io.github.arttvad9r.hermesbridge.protocol.CommandResultPayload.serializer(),
            result,
        ).toByteArray(Charsets.UTF_8).size.toLong()
        assertTrue(payloadBytes <= MAX_REMOTE_COMMAND_RESULT_PAYLOAD_BYTES)

        val envelopeBytes = BridgeProtocol.encode(
            BridgeProtocol.envelope(
                type = MessageType.COMMAND_RESULT,
                deviceId = "device_" + "d".repeat(80),
                payload = BridgeProtocol.payload(result),
            )
        ).toByteArray(Charsets.UTF_8).size.toLong()
        assertTrue(envelopeBytes < RELAY_WEBSOCKET_MAX_FRAME_BYTES)
    }

    private fun largePermission(index: Int): AppPermissionSnapshot {
        val prefix = "com.example.permission.p${index.toString().padStart(3, '0')}."
        return AppPermissionSnapshot(
            name = prefix + "x".repeat(MAX_ANDROID_QUALIFIED_NAME_CHARS - prefix.length),
            granted = true,
            protection = "dangerous\n" + "p".repeat(80),
            dangerous = true,
            group = "\u0800".repeat(200) + "\nprivate-group",
            implicit = false,
            neverForLocation = false,
        )
    }
}
