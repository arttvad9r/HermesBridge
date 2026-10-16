package io.github.arttvad9r.typeddroid

import io.github.arttvad9r.typeddroid.protocol.CommandRequestPayload
import io.github.arttvad9r.typeddroid.security.AgentTrustMode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiConvenienceToolRegistryTest {
    @Test
    fun `long press is a bounded stationary swipe`() = runBlocking {
        val ui = RecordingUiBackend()
        val result = registry(ui, AgentTrustMode.TRUSTED).execute(
            CommandRequestPayload(
                tool = BridgeToolRegistry.UI_LONG_PRESS,
                requestId = "long-press",
                arguments = buildJsonObject {
                    put("x", 123)
                    put("y", 456)
                    put("durationMillis", 900)
                },
            ),
        )

        assertTrue(result.ok)
        assertEquals(listOf(123, 456, 123, 456, 900), ui.lastSwipe)
    }

    @Test
    fun `wait for text polls hierarchy until match`() = runBlocking {
        val ui = RecordingUiBackend(
            hierarchies = ArrayDeque(listOf(
                "<hierarchy><node text=\"Loading\"/></hierarchy>",
                "<hierarchy><node text=\"Ready\"/></hierarchy>",
            )),
        )
        val result = registry(ui, AgentTrustMode.TRUSTED).execute(
            CommandRequestPayload(
                tool = BridgeToolRegistry.UI_WAIT_FOR_TEXT,
                requestId = "wait",
                arguments = buildJsonObject {
                    put("text", "ready")
                    put("timeoutMillis", 1_000)
                },
            ),
        )

        assertTrue(result.ok)
        assertTrue(ui.hierarchyCalls >= 2)
    }

    @Test
    fun `wait for text remains trusted only`() = runBlocking {
        val ui = RecordingUiBackend()
        val result = registry(ui, AgentTrustMode.SAFE).execute(
            CommandRequestPayload(
                tool = BridgeToolRegistry.UI_WAIT_FOR_TEXT,
                requestId = "wait-safe",
                arguments = buildJsonObject { put("text", "Ready") },
            ),
        )

        assertFalse(result.ok)
        assertEquals("policy_denied", result.error?.code)
        assertEquals(0, ui.hierarchyCalls)
    }

    private fun registry(ui: UiControlBackend, mode: AgentTrustMode) = BridgeToolRegistry(
        healthRepository = object : DeviceHealthRepository {
            override fun snapshot() = DeviceHealthSnapshot(null, 1, 1, 1, 1)
        },
        appsRepository = object : InstalledAppsRepository {
            override fun listLaunchableApps() = emptyList<InstalledAppSnapshot>()
        },
        filesRepository = object : SafFilesRepository {
            override fun list(pathSegments: List<String>) = SafDirectorySnapshot("root", pathSegments, emptyList())
        },
        uiControlBackend = ui,
        agentTrustMode = { mode },
    )

    private class RecordingUiBackend(
        private val hierarchies: ArrayDeque<String> = ArrayDeque(listOf("<hierarchy/>")),
    ) : UiControlBackend {
        var hierarchyCalls = 0
        var lastSwipe: List<Int>? = null

        override fun readiness() = PrivilegedBackendReadiness(ready = true)
        override suspend fun hierarchy(): UiHierarchyResult {
            hierarchyCalls += 1
            val value = if (hierarchies.size > 1) hierarchies.removeFirst() else hierarchies.first()
            return UiHierarchyResult(ok = true, xml = value)
        }
        override suspend fun screenshot() = UiScreenshotResult(false)
        override suspend fun tap(x: Int, y: Int) = PrivilegedOperationResult(ok = true)
        override suspend fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMillis: Int): PrivilegedOperationResult {
            lastSwipe = listOf(startX, startY, endX, endY, durationMillis)
            return PrivilegedOperationResult(ok = true)
        }
        override suspend fun text(value: String) = PrivilegedOperationResult(ok = true)
        override suspend fun key(keyCode: Int) = PrivilegedOperationResult(ok = true)
        override suspend fun openApp(packageName: String) = PrivilegedOperationResult(ok = true)
    }
}
