package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.protocol.CommandRequestPayload
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafToolRegistryTest {
    private val healthRepository = object : DeviceHealthRepository {
        override fun snapshot() = DeviceHealthSnapshot(null, 0, 0, 0, 0)
    }

    private val appsRepository = object : InstalledAppsRepository {
        override fun listLaunchableApps() = emptyList<InstalledAppSnapshot>()
    }

    @Test
    fun analyzeReturnsBoundedSummaryWithoutApproval() = runBlocking {
        BridgeApprovalRuntime.clear()
        val files = RecordingSafRepository()
        val result = registry(files).execute(
            request(
                tool = BridgeToolRegistry.FILES_ANALYZE,
                requestId = "analyze",
                path = listOf("Downloads"),
            )
        )

        assertTrue(result.ok)
        assertEquals("3", result.result?.get("fileCount")?.toString())
        assertEquals("6144", result.result?.get("totalBytes")?.toString())
        assertTrue(result.result?.get("largestFiles")?.toString()?.contains("large.iso") == true)
        assertTrue(BridgeApprovalRuntime.pendingTickets.value.isEmpty())
    }

    @Test
    fun deleteRequiresExactTargetApprovalBeforeMutation() = runBlocking {
        BridgeApprovalRuntime.clear()
        val files = RecordingSafRepository()
        val registry = registry(files)
        val request = request(
            tool = BridgeToolRegistry.FILES_DELETE,
            requestId = "delete-first",
            path = listOf("Downloads", "large.iso"),
        )

        val first = registry.execute(request)
        assertFalse(first.ok)
        assertEquals("approval_required", first.error?.code)
        assertEquals(0, files.deleteCalls)

        BridgeApprovalRuntime.approve(BridgeApprovalRuntime.pendingTickets.value.single().id)
        val second = registry.execute(request.copy(requestId = "delete-retry"))
        assertTrue(second.ok)
        assertEquals(1, files.deleteCalls)
        assertEquals(listOf("Downloads", "large.iso"), files.lastDeletedPath)
    }

    @Test
    fun deleteApprovalDoesNotSurviveTargetMetadataChange() = runBlocking {
        BridgeApprovalRuntime.clear()
        val files = RecordingSafRepository()
        val registry = registry(files)
        val initial = request(
            tool = BridgeToolRegistry.FILES_DELETE,
            requestId = "delete-initial",
            path = listOf("Downloads", "large.iso"),
        )

        registry.execute(initial)
        BridgeApprovalRuntime.approve(BridgeApprovalRuntime.pendingTickets.value.single().id)

        files.lastModified = 999L
        val changed = registry.execute(initial.copy(requestId = "delete-changed"))
        assertFalse(changed.ok)
        assertEquals("approval_required", changed.error?.code)
        assertEquals(0, files.deleteCalls)
    }

    @Test
    fun deleteRejectsGrantedRootAndTraversal() = runBlocking {
        BridgeApprovalRuntime.clear()
        val files = RecordingSafRepository()
        val registry = registry(files)

        val root = registry.execute(
            request(
                tool = BridgeToolRegistry.FILES_DELETE,
                requestId = "delete-root",
                path = emptyList(),
            )
        )
        assertFalse(root.ok)
        assertEquals("invalid_arguments", root.error?.code)

        val traversal = registry.execute(
            request(
                tool = BridgeToolRegistry.FILES_DELETE,
                requestId = "delete-traversal",
                path = listOf(".."),
            )
        )
        assertFalse(traversal.ok)
        assertEquals("invalid_arguments", traversal.error?.code)
        assertEquals(0, files.statCalls)
        assertEquals(0, files.deleteCalls)
    }

    private fun registry(files: SafFilesRepository) = BridgeToolRegistry(
        healthRepository = healthRepository,
        appsRepository = appsRepository,
        filesRepository = files,
    )

    private fun request(
        tool: String,
        requestId: String,
        path: List<String>,
    ) = CommandRequestPayload(
        tool = tool,
        requestId = requestId,
        arguments = buildJsonObject {
            put(
                "pathSegments",
                buildJsonArray { path.forEach { add(JsonPrimitive(it)) } },
            )
        },
    )

    private class RecordingSafRepository : SafFilesRepository {
        var statCalls = 0
        var deleteCalls = 0
        var lastModified = 123L
        var lastDeletedPath: List<String>? = null

        override fun list(pathSegments: List<String>) = SafDirectorySnapshot(
            rootName = "Shared",
            pathSegments = pathSegments,
            entries = emptyList(),
        )

        override fun analyze(pathSegments: List<String>) = SafAnalysisSnapshot(
            rootName = "Shared",
            pathSegments = pathSegments,
            scannedEntries = 4,
            fileCount = 3,
            directoryCount = 1,
            totalBytes = 6144L,
            truncated = false,
            largestFiles = listOf(
                SafLargeFileSnapshot(
                    name = "large.iso",
                    pathSegments = pathSegments + "large.iso",
                    mimeType = "application/octet-stream",
                    sizeBytes = 4096L,
                    lastModifiedEpochMillis = lastModified,
                )
            ),
        )

        override fun stat(pathSegments: List<String>): SafTargetSnapshot {
            statCalls += 1
            return SafTargetSnapshot(
                name = pathSegments.last(),
                pathSegments = pathSegments,
                directory = false,
                mimeType = "application/octet-stream",
                sizeBytes = 4096L,
                lastModifiedEpochMillis = lastModified,
            )
        }

        override fun delete(pathSegments: List<String>) {
            deleteCalls += 1
            lastDeletedPath = pathSegments
        }
    }
}
