package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.protocol.CommandRequestPayload
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeToolRegistryTest {
    private val healthRepository = object : DeviceHealthRepository {
        override fun snapshot() = DeviceHealthSnapshot(
            batteryPercent = 73,
            availableMemoryBytes = 10L,
            totalMemoryBytes = 20L,
            availableStorageBytes = 30L,
            totalStorageBytes = 40L,
        )
    }

    private val appsRepository = object : InstalledAppsRepository {
        override fun listLaunchableApps() = listOf(
            InstalledAppSnapshot(
                packageName = "com.example.app",
                label = "Example",
                versionName = "1.2.3",
                versionCode = 12L,
                systemApp = false,
                enabled = true,
            )
        )
    }

    private val filesRepository = object : SafFilesRepository {
        override fun list(pathSegments: List<String>) = SafDirectorySnapshot(
            rootName = "Shared",
            pathSegments = pathSegments,
            entries = listOf(
                SafFileEntrySnapshot(
                    name = "notes.txt",
                    pathSegments = pathSegments + "notes.txt",
                    directory = false,
                    mimeType = "text/plain",
                    sizeBytes = 123L,
                    lastModifiedEpochMillis = 456L,
                )
            ),
        )
    }

    private fun registry(
        privilegedAppsBackend: PrivilegedAppsBackend = DisabledPrivilegedAppsBackend,
        apkArtifactRepository: ApkArtifactRepository? = null,
        apkPackageInspector: ApkPackageInspector? = null,
    ) = BridgeToolRegistry(
        healthRepository = healthRepository,
        appsRepository = appsRepository,
        filesRepository = filesRepository,
        privilegedAppsBackend = privilegedAppsBackend,
        apkArtifactRepository = apkArtifactRepository,
        apkPackageInspector = apkPackageInspector,
    )

    @Test
    fun deviceHealthExecutesWithoutApproval() = runBlocking {
        val result = registry().execute(
            CommandRequestPayload(
                tool = BridgeToolRegistry.DEVICE_HEALTH,
                requestId = "request-1",
            )
        )
        assertTrue(result.ok)
        assertEquals("73", result.result?.get("batteryPercent")?.toString())
    }

    @Test
    fun appsListExecutesWithoutApproval() = runBlocking {
        val result = registry().execute(
            CommandRequestPayload(
                tool = BridgeToolRegistry.APPS_LIST,
                requestId = "request-apps",
            )
        )
        assertTrue(result.ok)
        assertEquals("1", result.result?.get("count")?.toString())
        assertTrue(result.result?.get("apps")?.toString()?.contains("com.example.app") == true)
    }

    @Test
    fun appsListRejectsArguments() = runBlocking {
        val result = registry().execute(
            CommandRequestPayload(
                tool = BridgeToolRegistry.APPS_LIST,
                requestId = "request-apps-args",
                arguments = buildJsonObject { put("unexpected", true) },
            )
        )
        assertFalse(result.ok)
        assertEquals("invalid_arguments", result.error?.code)
    }

    @Test
    fun filesListExecutesForSafePathSegments() = runBlocking {
        val result = registry().execute(
            CommandRequestPayload(
                tool = BridgeToolRegistry.FILES_LIST,
                requestId = "request-files",
                arguments = buildJsonObject {
                    put(
                        "pathSegments",
                        buildJsonArray {
                            add(JsonPrimitive("Documents"))
                            add(JsonPrimitive("Notes"))
                        },
                    )
                },
            )
        )
        assertTrue(result.ok)
        assertEquals("1", result.result?.get("count")?.toString())
        assertTrue(result.result?.toString()?.contains("notes.txt") == true)
    }

    @Test
    fun filesListRejectsTraversalSegments() = runBlocking {
        val result = registry().execute(
            CommandRequestPayload(
                tool = BridgeToolRegistry.FILES_LIST,
                requestId = "request-files-traversal",
                arguments = buildJsonObject {
                    put("pathSegments", buildJsonArray { add(JsonPrimitive("..")) })
                },
            )
        )
        assertFalse(result.ok)
        assertEquals("invalid_arguments", result.error?.code)
    }

    @Test
    fun filesListRejectsUnexpectedArguments() = runBlocking {
        val result = registry().execute(
            CommandRequestPayload(
                tool = BridgeToolRegistry.FILES_LIST,
                requestId = "request-files-extra",
                arguments = buildJsonObject { put("path", "../") },
            )
        )
        assertFalse(result.ok)
        assertEquals("invalid_arguments", result.error?.code)
    }

    @Test
    fun installRequiresApprovalBeforeBackendExecution() = runBlocking {
        BridgeApprovalRuntime.clear()
        val backend = RecordingPrivilegedAppsBackend()
        val artifacts = RecordingApkArtifactRepository()
        val inspector = RecordingApkPackageInspector()
        val registry = registry(backend, artifacts, inspector)

        val first = registry.execute(installRequest("install-first", artifactSuffix = "1"))
        assertFalse(first.ok)
        assertEquals("approval_required", first.error?.code)
        assertEquals(0, backend.installCalls)
        assertEquals(1, artifacts.downloadCalls)
        assertEquals(1, inspector.inspectCalls)

        val ticket = BridgeApprovalRuntime.pendingTickets.value.single()
        BridgeApprovalRuntime.approve(ticket.id)

        val second = registry.execute(
            installRequest(
                requestId = "install-retry",
                artifactSuffix = "2",
                fileName = "renamed-same-content.apk",
            )
        )
        assertTrue(second.ok)
        assertEquals(1, backend.installCalls)
        assertTrue(backend.lastReplace)
        assertEquals(TEST_SHA256, backend.lastInstalledArtifact?.sha256)
        assertTrue(BridgeApprovalRuntime.pendingTickets.value.isEmpty())
    }

    @Test
    fun installApprovalIsBoundToVerifiedContentNotTransportToken() = runBlocking {
        BridgeApprovalRuntime.clear()
        val backend = RecordingPrivilegedAppsBackend()
        val artifacts = RecordingApkArtifactRepository()
        val inspector = RecordingApkPackageInspector()
        val registry = registry(backend, artifacts, inspector)
        val initial = installRequest("install-initial", artifactSuffix = "1")

        registry.execute(initial)
        BridgeApprovalRuntime.approve(BridgeApprovalRuntime.pendingTickets.value.single().id)

        artifacts.overrideSha256 = "c".repeat(64)
        val changedContent = registry.execute(
            installRequest(
                requestId = "install-changed",
                artifactSuffix = "2",
                sha256 = "c".repeat(64),
            )
        )
        assertFalse(changedContent.ok)
        assertEquals("approval_required", changedContent.error?.code)
        assertEquals(0, backend.installCalls)

        artifacts.overrideSha256 = null
        val approvedOriginalContent = registry.execute(
            installRequest("install-approved", artifactSuffix = "3")
        )
        assertTrue(approvedOriginalContent.ok)
        assertEquals(1, backend.installCalls)

        val replay = registry.execute(installRequest("install-replay", artifactSuffix = "4"))
        assertFalse(replay.ok)
        assertEquals("approval_required", replay.error?.code)
        assertEquals(1, backend.installCalls)
    }

    @Test
    fun installDoesNotDownloadWhenShizukuIsUnavailable() = runBlocking {
        BridgeApprovalRuntime.clear()
        val artifacts = RecordingApkArtifactRepository()
        val result = registry(
            privilegedAppsBackend = DisabledPrivilegedAppsBackend,
            apkArtifactRepository = artifacts,
            apkPackageInspector = RecordingApkPackageInspector(),
        ).execute(installRequest("install-no-shizuku", artifactSuffix = "1"))

        assertFalse(result.ok)
        assertEquals("shizuku_unavailable", result.error?.code)
        assertEquals(0, artifacts.downloadCalls)
        assertTrue(BridgeApprovalRuntime.pendingTickets.value.isEmpty())
    }

    @Test
    fun installRejectsBridgeSelfReplacement() = runBlocking {
        BridgeApprovalRuntime.clear()
        val backend = RecordingPrivilegedAppsBackend()
        val artifacts = RecordingApkArtifactRepository()
        val inspector = RecordingApkPackageInspector(
            metadata = testMetadata(packageName = "io.github.arttvad9r.hermesbridge")
        )
        val result = registry(backend, artifacts, inspector).execute(
            installRequest("install-self", artifactSuffix = "1")
        )

        assertFalse(result.ok)
        assertEquals("protected_package", result.error?.code)
        assertEquals(0, backend.installCalls)
        assertTrue(BridgeApprovalRuntime.pendingTickets.value.isEmpty())
    }

    @Test
    fun installRejectsMalformedArtifactBeforeDownload() = runBlocking {
        val artifacts = RecordingApkArtifactRepository()
        val result = registry(
            privilegedAppsBackend = RecordingPrivilegedAppsBackend(),
            apkArtifactRepository = artifacts,
            apkPackageInspector = RecordingApkPackageInspector(),
        ).execute(
            installRequest(
                requestId = "install-invalid",
                artifactSuffix = "1",
                sha256 = "not-a-sha",
            )
        )

        assertFalse(result.ok)
        assertEquals("invalid_arguments", result.error?.code)
        assertEquals(0, artifacts.downloadCalls)
    }

    @Test
    fun pmInstallCommandUsesOnlySizeAndStdin() {
        assertArrayEquals(
            arrayOf("pm", "install", "-r", "-S", "1234", "-"),
            buildPmInstallCommand(1234L, replace = true),
        )
        assertArrayEquals(
            arrayOf("pm", "install", "-S", "1234", "-"),
            buildPmInstallCommand(1234L, replace = false),
        )
    }

    @Test
    fun uninstallRequiresApprovalBeforeBackendExecution() = runBlocking {
        BridgeApprovalRuntime.clear()
        val backend = RecordingPrivilegedAppsBackend()
        val registry = registry(backend)
        val request = uninstallRequest("request-uninstall", keepData = false)

        val first = registry.execute(request)
        assertFalse(first.ok)
        assertEquals("approval_required", first.error?.code)
        assertEquals(0, backend.uninstallCalls)
        val ticket = BridgeApprovalRuntime.pendingTickets.value.single()
        BridgeApprovalRuntime.approve(ticket.id)

        val second = registry.execute(request.copy(requestId = "request-uninstall-retry"))
        assertTrue(second.ok)
        assertEquals(1, backend.uninstallCalls)
        assertEquals("com.example.app", backend.lastPackageName)
        assertFalse(backend.lastKeepData)
        assertTrue(BridgeApprovalRuntime.pendingTickets.value.isEmpty())
    }

    @Test
    fun uninstallApprovalIsBoundToNormalizedArgumentsAndSingleUse() = runBlocking {
        BridgeApprovalRuntime.clear()
        val backend = RecordingPrivilegedAppsBackend()
        val registry = registry(backend)
        val initial = uninstallRequest("request-initial", keepData = false)

        registry.execute(initial)
        val ticket = BridgeApprovalRuntime.pendingTickets.value.single()
        BridgeApprovalRuntime.approve(ticket.id)

        val changedArguments = registry.execute(uninstallRequest("request-changed", keepData = true))
        assertFalse(changedArguments.ok)
        assertEquals("approval_required", changedArguments.error?.code)
        assertEquals(0, backend.uninstallCalls)

        val approvedOriginal = registry.execute(initial.copy(requestId = "request-approved-original"))
        assertTrue(approvedOriginal.ok)
        assertEquals(1, backend.uninstallCalls)

        val replay = registry.execute(initial.copy(requestId = "request-replay"))
        assertFalse(replay.ok)
        assertEquals("approval_required", replay.error?.code)
        assertEquals(1, backend.uninstallCalls)
    }

    @Test
    fun uninstallDoesNotCreateApprovalWhenShizukuIsUnavailable() = runBlocking {
        BridgeApprovalRuntime.clear()
        val result = registry().execute(uninstallRequest("request-no-shizuku", keepData = false))
        assertFalse(result.ok)
        assertEquals("shizuku_unavailable", result.error?.code)
        assertTrue(BridgeApprovalRuntime.pendingTickets.value.isEmpty())
    }

    @Test
    fun uninstallRejectsBridgeSelfUninstall() = runBlocking {
        BridgeApprovalRuntime.clear()
        val backend = RecordingPrivilegedAppsBackend()
        val result = registry(backend).execute(
            CommandRequestPayload(
                tool = BridgeToolRegistry.APPS_UNINSTALL,
                requestId = "request-self",
                arguments = buildJsonObject {
                    put("packageName", "io.github.arttvad9r.hermesbridge")
                    put("keepData", false)
                },
            )
        )
        assertFalse(result.ok)
        assertEquals("protected_package", result.error?.code)
        assertEquals(0, backend.uninstallCalls)
        assertTrue(BridgeApprovalRuntime.pendingTickets.value.isEmpty())
    }

    @Test
    fun uninstallRejectsInvalidPackageName() = runBlocking {
        BridgeApprovalRuntime.clear()
        val backend = RecordingPrivilegedAppsBackend()
        val result = registry(backend).execute(
            CommandRequestPayload(
                tool = BridgeToolRegistry.APPS_UNINSTALL,
                requestId = "request-invalid-package",
                arguments = buildJsonObject { put("packageName", "../other") },
            )
        )
        assertFalse(result.ok)
        assertEquals("invalid_arguments", result.error?.code)
        assertEquals(0, backend.uninstallCalls)
    }

    @Test
    fun forceStopRequiresApprovalBeforeBackendExecution() = runBlocking {
        BridgeApprovalRuntime.clear()
        val backend = RecordingPrivilegedAppsBackend()
        val registry = registry(backend)
        val request = forceStopRequest("request-force-stop")

        val first = registry.execute(request)
        assertFalse(first.ok)
        assertEquals("approval_required", first.error?.code)
        assertEquals(0, backend.forceStopCalls)

        val ticket = BridgeApprovalRuntime.pendingTickets.value.single()
        BridgeApprovalRuntime.approve(ticket.id)
        val second = registry.execute(request.copy(requestId = "request-force-stop-retry"))

        assertTrue(second.ok)
        assertEquals(1, backend.forceStopCalls)
        assertEquals("com.example.app", backend.lastForceStoppedPackage)
    }

    @Test
    fun forceStopApprovalIsSingleUse() = runBlocking {
        BridgeApprovalRuntime.clear()
        val backend = RecordingPrivilegedAppsBackend()
        val registry = registry(backend)
        val request = forceStopRequest("request-force-stop")

        registry.execute(request)
        BridgeApprovalRuntime.approve(BridgeApprovalRuntime.pendingTickets.value.single().id)
        assertTrue(registry.execute(request.copy(requestId = "request-approved")).ok)

        val replay = registry.execute(request.copy(requestId = "request-replay"))
        assertFalse(replay.ok)
        assertEquals("approval_required", replay.error?.code)
        assertEquals(1, backend.forceStopCalls)
    }

    @Test
    fun forceStopRejectsBridgeSelfStop() = runBlocking {
        BridgeApprovalRuntime.clear()
        val backend = RecordingPrivilegedAppsBackend()
        val result = registry(backend).execute(
            CommandRequestPayload(
                tool = BridgeToolRegistry.APPS_FORCE_STOP,
                requestId = "request-self-stop",
                arguments = buildJsonObject {
                    put("packageName", "io.github.arttvad9r.hermesbridge")
                },
            )
        )
        assertFalse(result.ok)
        assertEquals("protected_package", result.error?.code)
        assertEquals(0, backend.forceStopCalls)
    }

    @Test
    fun unknownToolFailsClosed() = runBlocking {
        val result = registry().execute(
            CommandRequestPayload(tool = "run_shell", requestId = "request-2")
        )
        assertFalse(result.ok)
        assertEquals("unknown_tool", result.error?.code)
    }

    @Test
    fun deviceHealthRejectsArguments() = runBlocking {
        val result = registry().execute(
            CommandRequestPayload(
                tool = BridgeToolRegistry.DEVICE_HEALTH,
                requestId = "request-3",
                arguments = buildJsonObject { put("unexpected", true) },
            )
        )
        assertFalse(result.ok)
        assertEquals("invalid_arguments", result.error?.code)
    }

    private fun installRequest(
        requestId: String,
        artifactSuffix: String,
        fileName: String = "example.apk",
        sha256: String = TEST_SHA256,
        replace: Boolean = true,
    ) = CommandRequestPayload(
        tool = BridgeToolRegistry.APPS_INSTALL,
        requestId = requestId,
        arguments = buildJsonObject {
            put("artifactId", "apk_123e4567-e89b-12d3-a456-42661417400$artifactSuffix")
            put("downloadToken", "A".repeat(43))
            put("fileName", fileName)
            put("sizeBytes", TEST_APK_SIZE)
            put("sha256", sha256)
            put("replace", replace)
        },
    )

    private fun uninstallRequest(requestId: String, keepData: Boolean) =
        CommandRequestPayload(
            tool = BridgeToolRegistry.APPS_UNINSTALL,
            requestId = requestId,
            arguments = buildJsonObject {
                put("packageName", "com.example.app")
                put("keepData", keepData)
            },
        )

    private fun forceStopRequest(requestId: String) =
        CommandRequestPayload(
            tool = BridgeToolRegistry.APPS_FORCE_STOP,
            requestId = requestId,
            arguments = buildJsonObject { put("packageName", "com.example.app") },
        )

    private class RecordingApkArtifactRepository : ApkArtifactRepository {
        var downloadCalls = 0
        var overrideSha256: String? = null

        override suspend fun download(descriptor: ApkArtifactDescriptor): VerifiedApkArtifact {
            downloadCalls += 1
            return VerifiedApkArtifact(
                file = File("/tmp/${descriptor.sha256}.apk"),
                fileName = descriptor.fileName,
                sizeBytes = descriptor.sizeBytes,
                sha256 = overrideSha256 ?: descriptor.sha256,
            )
        }
    }

    private class RecordingApkPackageInspector(
        private val metadata: ApkPackageMetadata = testMetadata(),
    ) : ApkPackageInspector {
        var inspectCalls = 0

        override fun inspect(artifact: VerifiedApkArtifact): ApkPackageMetadata {
            inspectCalls += 1
            return metadata
        }
    }

    private class RecordingPrivilegedAppsBackend : PrivilegedAppsBackend {
        var installCalls = 0
        var uninstallCalls = 0
        var forceStopCalls = 0
        var lastInstalledArtifact: VerifiedApkArtifact? = null
        var lastReplace = false
        var lastPackageName = ""
        var lastKeepData = false
        var lastForceStoppedPackage = ""

        override fun readiness() = PrivilegedBackendReadiness(ready = true)

        override suspend fun install(
            artifact: VerifiedApkArtifact,
            replace: Boolean,
        ): PrivilegedOperationResult {
            installCalls += 1
            lastInstalledArtifact = artifact
            lastReplace = replace
            return PrivilegedOperationResult(ok = true)
        }

        override suspend fun uninstall(
            packageName: String,
            keepData: Boolean,
        ): PrivilegedOperationResult {
            uninstallCalls += 1
            lastPackageName = packageName
            lastKeepData = keepData
            return PrivilegedOperationResult(ok = true)
        }

        override suspend fun forceStop(packageName: String): PrivilegedOperationResult {
            forceStopCalls += 1
            lastForceStoppedPackage = packageName
            return PrivilegedOperationResult(ok = true)
        }
    }

    companion object {
        private const val TEST_APK_SIZE = 1234L
        private val TEST_SHA256 = "a".repeat(64)
        private val TEST_SIGNER = "b".repeat(64)

        private fun testMetadata(
            packageName: String = "com.example.installed",
        ) = ApkPackageMetadata(
            packageName = packageName,
            versionName = "2.0",
            versionCode = 20L,
            signerSha256 = listOf(TEST_SIGNER),
        )
    }
}
