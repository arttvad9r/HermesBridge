package io.github.arttvad9r.typeddroid

import io.github.arttvad9r.typeddroid.protocol.CommandRequestPayload
import io.github.arttvad9r.typeddroid.security.AgentTrustMode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationToolRegistryTest {
    @Test
    fun `safe mode denies notification reads before backend`() = runBlocking {
        val backend = RecordingNotificationBackend()
        val result = registry(backend, AgentTrustMode.SAFE).execute(
            request(BridgeToolRegistry.NOTIFICATIONS_LIST, buildJsonObject { put("limit", 10) })
        )

        assertFalse(result.ok)
        assertEquals("policy_denied", result.error?.code)
        assertEquals(0, backend.listCalls)
    }

    @Test
    fun `trusted mode marks notification content untrusted`() = runBlocking {
        val backend = RecordingNotificationBackend()
        val result = registry(backend, AgentTrustMode.TRUSTED).execute(
            request(BridgeToolRegistry.NOTIFICATIONS_LIST, buildJsonObject { put("limit", 10) })
        )

        assertTrue(result.ok)
        assertEquals(JsonPrimitive(true), result.result?.get("untrustedContent"))
        val first = result.result?.get("notifications")
            ?.let { it as kotlinx.serialization.json.JsonArray }
            ?.first()?.jsonObject
        assertEquals(JsonPrimitive("external text"), first?.get("text"))
        assertEquals(1, backend.listCalls)
    }

    @Test
    fun `trusted reply reaches backend after validation`() = runBlocking {
        val backend = RecordingNotificationBackend()
        val result = registry(backend, AgentTrustMode.TRUSTED).execute(
            request(
                BridgeToolRegistry.NOTIFICATIONS_REPLY,
                buildJsonObject {
                    put("notificationKey", "key-1")
                    put("actionIndex", 1)
                    put("text", "reply")
                },
            )
        )

        assertTrue(result.ok)
        assertEquals("key-1", backend.replyKey)
        assertEquals(1, backend.replyIndex)
        assertEquals("reply", backend.replyText)
    }

    private fun request(tool: String, arguments: kotlinx.serialization.json.JsonObject) =
        CommandRequestPayload(tool = tool, requestId = "notification-test", arguments = arguments)

    private fun registry(backend: NotificationControlBackend, mode: AgentTrustMode) = BridgeToolRegistry(
        healthRepository = object : DeviceHealthRepository {
            override fun snapshot() = DeviceHealthSnapshot(null, 1, 1, 1, 1)
        },
        appsRepository = object : InstalledAppsRepository {
            override fun listLaunchableApps() = emptyList<InstalledAppSnapshot>()
        },
        filesRepository = object : SafFilesRepository {
            override fun list(pathSegments: List<String>) = SafDirectorySnapshot("root", pathSegments, emptyList())
        },
        notificationControlBackend = backend,
        agentTrustMode = { mode },
    )

    private class RecordingNotificationBackend : NotificationControlBackend {
        var listCalls = 0
        var replyKey: String? = null
        var replyIndex: Int? = null
        var replyText: String? = null

        override fun readiness() = PrivilegedBackendReadiness(ready = true)

        override suspend fun list(limit: Int): NotificationListResult {
            listCalls += 1
            return NotificationListResult(
                ok = true,
                notifications = listOf(
                    NotificationSnapshot(
                        key = "key-1",
                        packageName = "com.example.app",
                        postTimeEpochMillis = 123,
                        title = "title",
                        text = "external text",
                        subText = null,
                        category = null,
                        channelId = "channel",
                        groupKey = null,
                        ongoing = false,
                        clearable = true,
                        contentIntentAvailable = true,
                        actions = listOf(NotificationActionSnapshot(1, "Reply", true, 0, false)),
                    )
                ),
            )
        }

        override suspend fun dismiss(key: String) = PrivilegedOperationResult(ok = true)
        override suspend fun open(key: String) = PrivilegedOperationResult(ok = true)
        override suspend fun action(key: String, actionIndex: Int) = PrivilegedOperationResult(ok = true)
        override suspend fun reply(key: String, actionIndex: Int, text: String): PrivilegedOperationResult {
            replyKey = key
            replyIndex = actionIndex
            replyText = text
            return PrivilegedOperationResult(ok = true)
        }
    }
}
