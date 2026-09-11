package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.protocol.CommandRequestPayload
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppsInstallApprovalBindingTest {
    @After
    fun tearDown() {
        BridgeApprovalRuntime.clear()
    }

    @Test
    fun changingReplaceCannotConsumeApprovedInstall() = runBlocking {
        val backend = RecordingBackend()
        val registry = BridgeToolRegistry(
            healthRepository = object : DeviceHealthRepository {
                override fun snapshot() = DeviceHealthSnapshot(null, 0L, 0L, 0L, 0L)
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
                    signerSha256 = listOf(SIGNER_SHA256),
                )
            },
        )

        val initial = request("install-initial", artifactSuffix = "1", replace = true)
        val first = registry.execute(initial)
        assertFalse(first.ok)
        assertEquals("approval_required", first.error?.code)
        BridgeApprovalRuntime.approve(BridgeApprovalRuntime.pendingTickets.value.single().id)

        val changed = registry.execute(
            request("install-changed", artifactSuffix = "2", replace = false)
        )
        assertFalse(changed.ok)
        assertEquals("approval_required", changed.error?.code)
        assertEquals(0, backend.installCalls)

        val approvedOriginal = registry.execute(
            request("install-approved", artifactSuffix = "3", replace = true)
        )
        assertTrue(approvedOriginal.ok)
        assertEquals(1, backend.installCalls)
        assertTrue(backend.lastReplace)
    }

    private fun request(
        requestId: String,
        artifactSuffix: String,
        replace: Boolean,
    ) = CommandRequestPayload(
        tool = BridgeToolRegistry.APPS_INSTALL,
        requestId = requestId,
        arguments = buildJsonObject {
            put("artifactId", "apk_123e4567-e89b-12d3-a456-42661417400$artifactSuffix")
            put("downloadToken", "A".repeat(43))
            put("fileName", "fixture.apk")
            put("sizeBytes", 1234L)
            put("sha256", APK_SHA256)
            put("replace", replace)
        },
    )

    private class RecordingBackend : PrivilegedAppsBackend {
        var installCalls = 0
        var lastReplace = false

        override fun readiness() = PrivilegedBackendReadiness(ready = true)

        override suspend fun install(
            artifact: VerifiedApkArtifact,
            replace: Boolean,
        ): PrivilegedOperationResult {
            installCalls += 1
            lastReplace = replace
            return PrivilegedOperationResult(ok = true)
        }

        override suspend fun uninstall(
            packageName: String,
            keepData: Boolean,
        ) = PrivilegedOperationResult(ok = false)

        override suspend fun forceStop(packageName: String) = PrivilegedOperationResult(ok = false)
    }

    private companion object {
        const val PACKAGE_NAME = "io.github.arttvad9r.hermesbridge.fixture"
        val APK_SHA256 = "a".repeat(64)
        val SIGNER_SHA256 = "b".repeat(64)
    }
}
