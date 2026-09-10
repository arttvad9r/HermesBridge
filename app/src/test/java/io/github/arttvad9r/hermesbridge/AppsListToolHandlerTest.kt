package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.protocol.BridgeProtocol
import io.github.arttvad9r.hermesbridge.protocol.CommandRequestPayload
import io.github.arttvad9r.hermesbridge.protocol.MessageType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppsListToolHandlerTest {
    @Test
    fun projectionBoundsCountAndRemoteStringsWithoutChangingSourceList() {
        val source = (0 until 200).map(::largeApp)
        val projection = projectAppsForRemoteResult(source)

        assertEquals(200, projection.totalVisibleCount)
        assertEquals(MAX_REMOTE_APP_ENTRIES, projection.apps.size)
        assertTrue(projection.truncated)
        assertEquals(200, source.size)
        projection.apps.forEach { app ->
            assertTrue(app.label.length <= MAX_REMOTE_APP_LABEL_CHARS)
            assertTrue(app.versionName.orEmpty().length <= MAX_REMOTE_APP_VERSION_NAME_CHARS)
            assertFalse(app.label.any { it.isISOControl() })
            assertFalse(app.versionName.orEmpty().any { it.isISOControl() })
        }
    }

    @Test
    fun remoteTextNormalizationReplacesControlsBeforeTruncation() {
        val raw = "A\u0000B\nC\tD".repeat(100)
        val projected = boundedRemoteText(raw, 20)

        assertEquals(20, projected.length)
        assertFalse(projected.any { it.isISOControl() })
        assertTrue(projected.contains(' '))
    }

    @Test
    fun appsListWireEnvelopeKeepsSafetyMarginBelowWebSocketFrameLimit() {
        val repository = object : InstalledAppsRepository {
            override fun listLaunchableApps() = (0 until AndroidInstalledAppsRepository.MAX_APPS)
                .map(::largeApp)
        }
        val result = AppsListToolHandler(repository).execute(
            CommandRequestPayload(
                tool = AppsListToolHandler.TOOL_NAME,
                requestId = "apps-list-bounds",
            )
        )

        assertTrue(result.ok)
        assertEquals(MAX_REMOTE_APP_ENTRIES.toString(), result.result?.get("count")?.toString())
        assertEquals(
            AndroidInstalledAppsRepository.MAX_APPS.toString(),
            result.result?.get("totalVisibleCount")?.toString(),
        )
        assertEquals("true", result.result?.get("truncated")?.toString())

        val encoded = BridgeProtocol.encode(
            BridgeProtocol.envelope(
                type = MessageType.COMMAND_RESULT,
                deviceId = "device_" + "d".repeat(80),
                payload = BridgeProtocol.payload(result),
            )
        )
        val encodedBytes = encoded.toByteArray(Charsets.UTF_8).size.toLong()
        assertTrue(
            "Encoded apps.list frame was $encodedBytes bytes",
            encodedBytes <= RELAY_WEBSOCKET_MAX_FRAME_BYTES - FRAME_SAFETY_MARGIN_BYTES,
        )
    }

    @Test
    fun invalidRepositoryPackageIsNotExposedRemotely() {
        val repository = object : InstalledAppsRepository {
            override fun listLaunchableApps() = listOf(
                largeApp(1),
                largeApp(2).copy(packageName = "../not-a-package"),
            )
        }
        val result = AppsListToolHandler(repository).execute(
            CommandRequestPayload(
                tool = AppsListToolHandler.TOOL_NAME,
                requestId = "apps-list-invalid-package",
            )
        )

        assertTrue(result.ok)
        assertEquals("1", result.result?.get("count")?.toString())
        assertEquals("2", result.result?.get("totalVisibleCount")?.toString())
        assertEquals("true", result.result?.get("truncated")?.toString())
        assertFalse(result.result.toString().contains("../not-a-package"))
    }

    @Test
    fun commandRouterUsesBoundedAppsListHandler() = runBlocking {
        val repository = object : InstalledAppsRepository {
            override fun listLaunchableApps() = (0 until 200).map(::largeApp)
        }
        val router = BridgeCommandRouter(
            coreRegistry = BridgeToolRegistry(
                healthRepository = object : DeviceHealthRepository {
                    override fun snapshot() = DeviceHealthSnapshot(null, 1, 2, 3, 4)
                },
                appsRepository = repository,
                filesRepository = object : SafFilesRepository {
                    override fun list(pathSegments: List<String>) = SafDirectorySnapshot(
                        rootName = "Shared",
                        pathSegments = pathSegments,
                        entries = emptyList(),
                    )
                },
            ),
            appsRepository = repository,
            appPermissionsRepository = null,
        )

        val result = router.execute(
            CommandRequestPayload(
                tool = AppsListToolHandler.TOOL_NAME,
                requestId = "router-app-list-bounds",
            )
        )

        assertTrue(result.ok)
        assertEquals(MAX_REMOTE_APP_ENTRIES.toString(), result.result?.get("count")?.toString())
        assertEquals("200", result.result?.get("totalVisibleCount")?.toString())
        assertEquals("true", result.result?.get("truncated")?.toString())
    }

    private fun largeApp(index: Int): InstalledAppSnapshot {
        val suffix = index.toString().padStart(3, '0')
        val packageName = "com.example.app$suffix." + "a".repeat(180)
        val expensiveUnicode = "\u0800".repeat(300)
        val controls = "\u0000\n\t".repeat(100)
        return InstalledAppSnapshot(
            packageName = packageName,
            label = expensiveUnicode + controls,
            versionName = expensiveUnicode + controls,
            versionCode = Long.MAX_VALUE,
            systemApp = true,
            enabled = true,
        )
    }

    companion object {
        private const val FRAME_SAFETY_MARGIN_BYTES = 32L * 1024L
    }
}
