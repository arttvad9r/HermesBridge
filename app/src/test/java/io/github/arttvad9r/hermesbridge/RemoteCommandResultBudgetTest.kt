package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.protocol.BridgeProtocol
import io.github.arttvad9r.hermesbridge.protocol.CommandRequestPayload
import io.github.arttvad9r.hermesbridge.protocol.CommandResultPayload
import io.github.arttvad9r.hermesbridge.protocol.MessageType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteCommandResultBudgetTest {
    @Test
    fun smallResultIsUnchanged() {
        val result = CommandResultPayload(
            requestId = "small-result",
            ok = true,
            result = buildJsonObject { put("status", "ok") },
        )

        assertSame(result, enforceRemoteCommandResultBudget(result))
    }

    @Test
    fun oversizedSuccessFailsClosedWithoutReturningOriginalPayload() {
        val marker = "SECRET_OVERSIZED_MARKER"
        val result = CommandResultPayload(
            requestId = "oversized-result",
            ok = true,
            result = buildJsonObject {
                put("data", marker + "x".repeat(MAX_REMOTE_COMMAND_RESULT_PAYLOAD_BYTES.toInt()))
            },
        )

        val bounded = enforceRemoteCommandResultBudget(result)

        assertFalse(bounded.ok)
        assertNull(bounded.result)
        assertEquals(RESULT_TOO_LARGE_ERROR_CODE, bounded.error?.code)
        assertEquals(
            "The Android command result exceeded the control-channel safety limit.",
            bounded.error?.message,
        )
        assertFalse(bounded.toString().contains(marker))
    }

    @Test
    fun fallbackFailureEnvelopeStaysBelowWebSocketFrameLimit() {
        val oversized = CommandResultPayload(
            requestId = "r".repeat(128),
            ok = true,
            result = buildJsonObject {
                put("data", "\u0800".repeat(MAX_REMOTE_COMMAND_RESULT_PAYLOAD_BYTES.toInt()))
            },
        )
        val bounded = enforceRemoteCommandResultBudget(oversized)
        val encoded = BridgeProtocol.encode(
            BridgeProtocol.envelope(
                type = MessageType.COMMAND_RESULT,
                deviceId = "device_" + "d".repeat(80),
                payload = BridgeProtocol.payload(bounded),
            )
        )

        assertTrue(encoded.toByteArray(Charsets.UTF_8).size.toLong() < RELAY_WEBSOCKET_MAX_FRAME_BYTES)
    }

    @Test
    fun commandRouterAppliesBudgetToSpecializedHandlerResult() = runBlocking {
        val packageName = "com.example.large"
        val appsRepository = object : InstalledAppsRepository {
            override fun listLaunchableApps() = listOf(
                InstalledAppSnapshot(
                    packageName = packageName,
                    label = "Large",
                    versionName = "1",
                    versionCode = 1L,
                    systemApp = false,
                    enabled = true,
                )
            )
        }
        val permissionsRepository = object : AppPermissionsRepository {
            override fun read(packageName: String) = AppPermissionsSnapshot(
                packageName = packageName,
                permissions = (0 until 1_000).map { index ->
                    AppPermissionSnapshot(
                        name = "com.example.permission.$index." + "x".repeat(220),
                        granted = true,
                        protection = "dangerous",
                        dangerous = true,
                        group = "group." + "\u0800".repeat(220),
                        implicit = false,
                        neverForLocation = false,
                    )
                },
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
                requestId = "router-result-budget",
                arguments = buildJsonObject {
                    put("packageName", JsonPrimitive(packageName))
                },
            )
        )

        assertFalse(result.ok)
        assertNull(result.result)
        assertEquals(RESULT_TOO_LARGE_ERROR_CODE, result.error?.code)
    }
}
