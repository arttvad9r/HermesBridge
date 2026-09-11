package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.protocol.CommandRequestPayload
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CommandExecutionBoundaryTest {
    @After
    fun tearDown() {
        BridgeApprovalRuntime.clear()
        BridgeAuditRuntime.clear()
    }

    @Test
    fun unexpectedReadOnlyExceptionBecomesStructuredFailure() = runBlocking {
        val marker = "SECRET_MARKER:/data/user/0/private"
        val apps = EmptyAppsRepository()
        val router = BridgeCommandRouter(
            coreRegistry = BridgeToolRegistry(
                healthRepository = object : DeviceHealthRepository {
                    override fun snapshot(): DeviceHealthSnapshot {
                        throw IllegalStateException(marker)
                    }
                },
                appsRepository = apps,
                filesRepository = EmptyFilesRepository(),
            ),
            appsRepository = apps,
            appPermissionsRepository = null,
        )

        val result = router.execute(
            CommandRequestPayload(
                tool = BridgeToolRegistry.DEVICE_HEALTH,
                requestId = "read-only-exception",
            )
        )

        assertFalse(result.ok)
        assertEquals("command_execution_failed", result.error?.code)
        assertEquals(
            "Command execution failed before a safe result could be produced.",
            result.error?.message,
        )
        assertFalse(result.error?.message.orEmpty().contains(marker))
    }

    @Test
    fun unexpectedInstallExceptionIsTerminalForExactRetry() = runBlocking {
        BridgeApprovalRuntime.clear()
        val backend = ThrowingInstallBackend()
        val apps = EmptyAppsRepository()
        val registry = BridgeToolRegistry(
            healthRepository = StableHealthRepository(),
            appsRepository = apps,
            filesRepository = EmptyFilesRepository(),
            privilegedAppsBackend = backend,
            apkArtifactRepository = object : ApkArtifactRepository {
                override suspend fun download(descriptor: ApkArtifactDescriptor) = VerifiedApkArtifact(
                    file = File("/tmp/${descriptor.sha256}.apk"),
                    fileName = descriptor.fileName,
                    sizeBytes = descriptor.sizeBytes,
                    sha256 = descriptor.sha256,
                )
            },
            apkPackageInspector = object : ApkPackageInspector {
                override fun inspect(artifact: VerifiedApkArtifact) = ApkPackageMetadata(
                    packageName = PACKAGE_NAME,
                    versionName = "1.0",
                    versionCode = 1L,
                    signerSha256 = listOf("b".repeat(64)),
                )
            },
        )
        val router = BridgeCommandRouter(
            coreRegistry = registry,
            appsRepository = apps,
            appPermissionsRepository = null,
            privilegedAppsBackend = backend,
        )
        val request = CommandRequestPayload(
            tool = BridgeToolRegistry.APPS_INSTALL,
            requestId = "install-exception",
            arguments = buildJsonObject {
                put("artifactId", "apk_123e4567-e89b-12d3-a456-426614174001")
                put("downloadToken", "A".repeat(43))
                put("fileName", "fixture.apk")
                put("sizeBytes", 1234L)
                put("sha256", "a".repeat(64))
                put("replace", true)
            },
        )

        val approvalRequired = router.execute(request)
        assertFalse(approvalRequired.ok)
        assertEquals("approval_required", approvalRequired.error?.code)
        BridgeApprovalRuntime.approve(BridgeApprovalRuntime.pendingTickets.value.single().id)

        val failed = router.execute(request)
        assertFalse(failed.ok)
        assertEquals("command_execution_failed", failed.error?.code)
        assertFalse(failed.error?.message.orEmpty().contains(ThrowingInstallBackend.MARKER))
        assertEquals(1, backend.installCalls)

        val exactRetry = router.execute(request)
        assertFalse(exactRetry.ok)
        assertEquals("command_execution_failed", exactRetry.error?.code)
        assertEquals(1, backend.installCalls)
    }

    private class StableHealthRepository : DeviceHealthRepository {
        override fun snapshot() = DeviceHealthSnapshot(
            batteryPercent = 50,
            availableMemoryBytes = 1L,
            totalMemoryBytes = 2L,
            availableStorageBytes = 3L,
            totalStorageBytes = 4L,
        )
    }

    private class EmptyAppsRepository : InstalledAppsRepository {
        override fun listLaunchableApps() = emptyList<InstalledAppSnapshot>()
    }

    private class EmptyFilesRepository : SafFilesRepository {
        override fun list(pathSegments: List<String>) = SafDirectorySnapshot(
            rootName = "Shared",
            pathSegments = pathSegments,
            entries = emptyList(),
        )
    }

    private class ThrowingInstallBackend : PrivilegedAppsBackend {
        var installCalls = 0

        override fun readiness() = PrivilegedBackendReadiness(ready = true)

        override suspend fun install(
            artifact: VerifiedApkArtifact,
            replace: Boolean,
        ): PrivilegedOperationResult {
            installCalls += 1
            throw IllegalStateException(MARKER)
        }

        override suspend fun uninstall(
            packageName: String,
            keepData: Boolean,
        ) = PrivilegedOperationResult(ok = false)

        override suspend fun forceStop(packageName: String) = PrivilegedOperationResult(ok = false)

        companion object {
            const val MARKER = "SECRET_INSTALL_EXCEPTION"
        }
    }

    private companion object {
        const val PACKAGE_NAME = "io.github.arttvad9r.hermesbridge.fixture"
    }
}
