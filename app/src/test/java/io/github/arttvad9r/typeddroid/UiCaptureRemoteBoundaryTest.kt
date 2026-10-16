package io.github.arttvad9r.typeddroid

import io.github.arttvad9r.typeddroid.protocol.CommandRequestPayload
import io.github.arttvad9r.typeddroid.security.AgentTrustMode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiCaptureRemoteBoundaryTest {
    private val healthRepository = object : DeviceHealthRepository {
        override fun snapshot() = DeviceHealthSnapshot(null, 1L, 2L, 3L, 4L)
    }
    private val appsRepository = object : InstalledAppsRepository {
        override fun listLaunchableApps() = emptyList<InstalledAppSnapshot>()
    }
    private val filesRepository = object : SafFilesRepository {
        override fun list(pathSegments: List<String>) = SafDirectorySnapshot("Shared", pathSegments, emptyList())
    }
    private val uiBackend = object : UiControlBackend {
        override fun readiness() = PrivilegedBackendReadiness(ready = true)
        override suspend fun hierarchy() = UiHierarchyResult(ok = true, xml = "<hierarchy />")
        override suspend fun screenshot() = UiScreenshotResult(
            ok = true,
            bytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()),
            width = 100,
            height = 200,
            mimeType = "image/jpeg",
        )
        override suspend fun tap(x: Int, y: Int) = PrivilegedOperationResult(ok = true)
        override suspend fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMillis: Int) = PrivilegedOperationResult(ok = true)
        override suspend fun text(value: String) = PrivilegedOperationResult(ok = true)
        override suspend fun key(keyCode: Int) = PrivilegedOperationResult(ok = true)
        override suspend fun openApp(packageName: String) = PrivilegedOperationResult(ok = true)
    }

    private fun registry(trust: AgentTrustMode) = BridgeToolRegistry(
        healthRepository = healthRepository,
        appsRepository = appsRepository,
        filesRepository = filesRepository,
        uiControlBackend = uiBackend,
        agentTrustMode = { trust },
    )

    @Test
    fun `safe pairing cannot remotely inspect or control UI`() = runBlocking {
        val registry = registry(AgentTrustMode.SAFE)
        for (tool in listOf("ui.hierarchy", "ui.screenshot", "ui.tap", "ui.swipe", "ui.text", "ui.key", "apps.open")) {
            val arguments = when (tool) {
                "ui.tap" -> kotlinx.serialization.json.buildJsonObject { put("x", 10); put("y", 20) }
                "ui.swipe" -> kotlinx.serialization.json.buildJsonObject { put("startX", 1); put("startY", 2); put("endX", 3); put("endY", 4) }
                "ui.text" -> kotlinx.serialization.json.buildJsonObject { put("text", "hello") }
                "ui.key" -> kotlinx.serialization.json.buildJsonObject { put("keyCode", 4) }
                "apps.open" -> kotlinx.serialization.json.buildJsonObject { put("packageName", "com.example.app") }
                else -> kotlinx.serialization.json.JsonObject(emptyMap())
            }
            val result = registry.execute(CommandRequestPayload(tool = tool, requestId = "safe-$tool", arguments = arguments))
            assertFalse(tool, result.ok)
            assertEquals(tool, "policy_denied", result.error?.code)
        }
    }

    @Test
    fun `trusted pairing can use typed UI tools`() = runBlocking {
        val registry = registry(AgentTrustMode.TRUSTED)
        val screenshot = registry.execute(CommandRequestPayload(tool = "ui.screenshot", requestId = "shot"))
        val hierarchy = registry.execute(CommandRequestPayload(tool = "ui.hierarchy", requestId = "tree"))
        val tap = registry.execute(CommandRequestPayload(
            tool = "ui.tap",
            requestId = "tap",
            arguments = kotlinx.serialization.json.buildJsonObject { put("x", 10); put("y", 20) },
        ))

        assertTrue(screenshot.ok)
        assertEquals("image/jpeg", screenshot.result?.get("mimeType")?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertTrue(hierarchy.ok)
        assertTrue(tap.ok)
    }

    @Test
    fun `legacy generic capture aliases remain unregistered`() = runBlocking {
        val registry = registry(AgentTrustMode.TRUSTED)
        for (tool in listOf("screen.capture", "screen.screenshot", "ui.capture")) {
            val result = registry.execute(CommandRequestPayload(tool = tool, requestId = "legacy-$tool"))
            assertEquals("unknown_tool", result.error?.code)
        }
    }
}
