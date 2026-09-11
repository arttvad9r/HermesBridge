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

class ForceStopApprovalBindingTest {
    @After
    fun tearDown() {
        BridgeApprovalRuntime.clear()
    }

    @Test
    fun changedPackageCannotConsumeApprovedForceStop() = runBlocking {
        BridgeApprovalRuntime.clear()
        val backend = RecordingBackend()
        val registry = BridgeToolRegistry(
            healthRepository = object : DeviceHealthRepository {
                override fun snapshot() = DeviceHealthSnapshot(
                    batteryPercent = 73,
                    availableMemoryBytes = 10L,
                    totalMemoryBytes = 20L,
                    availableStorageBytes = 30L,
                    totalStorageBytes = 40L,
                )
            },
            appsRepository = object : InstalledAppsRepository {
                override fun listLaunchableApps() = emptyList<InstalledAppSnapshot>()
            },
            filesRepository = object : SafFilesRepository {
                override fun list(pathSegments: List<String>) = SafDirectorySnapshot(
                    rootName = "Shared",
                    pathSegments = pathSegments,
                    entries = emptyList(),
                )
            },
            privilegedAppsBackend = backend,
        )

        val original = request("force-initial", FIRST_PACKAGE)
        val first = registry.execute(original)
        assertFalse(first.ok)
        assertEquals("approval_required", first.error?.code)
        BridgeApprovalRuntime.approve(BridgeApprovalRuntime.pendingTickets.value.single().id)

        val changed = registry.execute(request("force-changed", SECOND_PACKAGE))
        assertFalse(changed.ok)
        assertEquals("approval_required", changed.error?.code)
        assertEquals(0, backend.forceStopCalls)

        val approvedOriginal = registry.execute(original.copy(requestId = "force-approved"))
        assertTrue(approvedOriginal.ok)
        assertEquals(1, backend.forceStopCalls)
        assertEquals(FIRST_PACKAGE, backend.lastPackage)
    }

    private fun request(requestId: String, packageName: String) = CommandRequestPayload(
        tool = BridgeToolRegistry.APPS_FORCE_STOP,
        requestId = requestId,
        arguments = buildJsonObject { put("packageName", packageName) },
    )

    private class RecordingBackend : PrivilegedAppsBackend {
        var forceStopCalls = 0
        var lastPackage = ""

        override fun readiness() = PrivilegedBackendReadiness(ready = true)

        override suspend fun install(
            artifact: VerifiedApkArtifact,
            replace: Boolean,
        ) = PrivilegedOperationResult(ok = false)

        override suspend fun uninstall(
            packageName: String,
            keepData: Boolean,
        ) = PrivilegedOperationResult(ok = false)

        override suspend fun forceStop(packageName: String): PrivilegedOperationResult {
            forceStopCalls += 1
            lastPackage = packageName
            return PrivilegedOperationResult(ok = true)
        }
    }

    private companion object {
        const val FIRST_PACKAGE = "io.github.arttvad9r.hermesbridge.fixture"
        const val SECOND_PACKAGE = "io.github.arttvad9r.hermesbridge.fixture.other"
    }
}
