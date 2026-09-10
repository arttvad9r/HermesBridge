package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.protocol.BridgeProtocol
import io.github.arttvad9r.hermesbridge.protocol.CommandRequestPayload
import io.github.arttvad9r.hermesbridge.protocol.CommandResultPayload
import io.github.arttvad9r.hermesbridge.protocol.MessageType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteFilesAnalyzeProjectionTest {
    @Test
    fun exactFileIdentityIsPreservedWhileDisplayTextAndTruncationFlagsAreProjected() {
        val exactName = "report\n\u0800-final.bin"
        val source = analysisResult(
            pathSegments = listOf("Documents"),
            files = listOf(
                largeFile(
                    name = exactName,
                    pathSegments = listOf("Documents", exactName),
                    mimeType = "application/octet-stream\n" + "m".repeat(300),
                )
            ),
            rootName = "Shared\n" + "r".repeat(300),
            truncated = true,
        )

        val projected = projectFilesAnalyzeForRemoteResult(source)
        val files = projected.result?.get("largestFiles") as JsonArray
        val file = files.single().jsonObject

        assertEquals(JsonPrimitive(exactName), file["name"])
        assertEquals(
            buildJsonArray {
                add(JsonPrimitive("Documents"))
                add(JsonPrimitive(exactName))
            },
            file["pathSegments"],
        )
        val rootName = projected.result?.get("rootName")?.jsonPrimitive?.content.orEmpty()
        val mimeType = file["mimeType"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(rootName.length <= MAX_REMOTE_FILE_ROOT_NAME_CHARS)
        assertTrue(mimeType.length <= MAX_REMOTE_FILE_MIME_TYPE_CHARS)
        assertFalse(rootName.any(Char::isISOControl))
        assertFalse(mimeType.any(Char::isISOControl))
        assertEquals("true", projected.result?.get("scanTruncated")?.toString())
        assertEquals("false", projected.result?.get("resultTruncated")?.toString())
        assertEquals("true", projected.result?.get("truncated")?.toString())
        assertEquals("1", projected.result?.get("totalLargestFileCount")?.toString())
        assertEquals("1", projected.result?.get("returnedLargestFileCount")?.toString())
    }

    @Test
    fun malformedOrOutOfSubtreeFileIdentityIsDroppedInsteadOfRewritten() {
        val source = analysisResult(
            pathSegments = listOf("Documents"),
            files = listOf(
                largeFile(
                    name = "valid.bin",
                    pathSegments = listOf("Documents", "valid.bin"),
                    mimeType = "application/octet-stream",
                ),
                largeFile(
                    name = "..",
                    pathSegments = listOf("Documents", ".."),
                    mimeType = "application/octet-stream",
                ),
                largeFile(
                    name = "escape.bin",
                    pathSegments = listOf("Other", "escape.bin"),
                    mimeType = "application/octet-stream",
                ),
            ),
        )

        val projected = projectFilesAnalyzeForRemoteResult(source)
        val files = projected.result?.get("largestFiles") as JsonArray

        assertEquals(1, files.size)
        assertEquals(JsonPrimitive("valid.bin"), files.single().jsonObject["name"])
        assertEquals("false", projected.result?.get("scanTruncated")?.toString())
        assertEquals("true", projected.result?.get("resultTruncated")?.toString())
        assertEquals("true", projected.result?.get("truncated")?.toString())
        assertEquals("3", projected.result?.get("totalLargestFileCount")?.toString())
        assertEquals("1", projected.result?.get("returnedLargestFileCount")?.toString())
    }

    @Test
    fun routerTruncatesWorstCaseRecursivePathsBeforeGenericSizeFailure() = runBlocking {
        val parentPath = List(31) { index ->
            "d${index.toString().padStart(2, '0')}-" + "\u0800".repeat(251)
        }
        val snapshots = (0 until AndroidSafFilesRepository.MAX_LARGEST_FILES).map { index ->
            val suffix = index.toString().padStart(2, '0')
            val name = "f$suffix-" + "\u0800".repeat(251)
            SafLargeFileSnapshot(
                name = name,
                pathSegments = parentPath + name,
                mimeType = "application/" + "\u0800".repeat(300),
                sizeBytes = Long.MAX_VALUE - index,
                lastModifiedEpochMillis = Long.MAX_VALUE,
            )
        }
        val appsRepository = object : InstalledAppsRepository {
            override fun listLaunchableApps() = emptyList<InstalledAppSnapshot>()
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

                    override fun analyze(pathSegments: List<String>) = SafAnalysisSnapshot(
                        rootName = "Shared\n" + "r".repeat(300),
                        pathSegments = pathSegments,
                        scannedEntries = snapshots.size,
                        fileCount = snapshots.size,
                        directoryCount = 0,
                        totalBytes = Long.MAX_VALUE,
                        truncated = false,
                        largestFiles = snapshots,
                    )
                },
            ),
            appsRepository = appsRepository,
            appPermissionsRepository = null,
        )

        val result = router.execute(
            CommandRequestPayload(
                tool = BridgeToolRegistry.FILES_ANALYZE,
                requestId = "files-analyze-result-bounds",
                arguments = buildJsonObject {
                    put(
                        "pathSegments",
                        buildJsonArray { parentPath.forEach { add(JsonPrimitive(it)) } },
                    )
                },
            )
        )
        val returned = result.result?.get("largestFiles") as JsonArray

        assertTrue(result.ok)
        assertTrue(returned.isNotEmpty())
        assertTrue(returned.size < snapshots.size)
        assertEquals(
            snapshots.first().name,
            returned.first().jsonObject["name"]?.jsonPrimitive?.content,
        )
        assertEquals("false", result.result?.get("scanTruncated")?.toString())
        assertEquals("true", result.result?.get("resultTruncated")?.toString())
        assertEquals("true", result.result?.get("truncated")?.toString())
        assertEquals(snapshots.size.toString(), result.result?.get("totalLargestFileCount")?.toString())
        assertEquals(returned.size.toString(), result.result?.get("returnedLargestFileCount")?.toString())
        assertEquals(snapshots.size.toString(), result.result?.get("scannedEntries")?.toString())
        assertEquals(snapshots.size.toString(), result.result?.get("fileCount")?.toString())
        assertEquals("0", result.result?.get("directoryCount")?.toString())
        assertEquals(Long.MAX_VALUE.toString(), result.result?.get("totalBytes")?.toString())
        assertTrue(remoteCommandResultPayloadBytes(result) <= MAX_REMOTE_COMMAND_RESULT_PAYLOAD_BYTES)

        val envelopeBytes = BridgeProtocol.encode(
            BridgeProtocol.envelope(
                type = MessageType.COMMAND_RESULT,
                deviceId = "device_" + "d".repeat(80),
                payload = BridgeProtocol.payload(result),
            )
        ).toByteArray(Charsets.UTF_8).size.toLong()
        assertTrue(envelopeBytes < RELAY_WEBSOCKET_MAX_FRAME_BYTES)
    }

    private fun analysisResult(
        pathSegments: List<String>,
        files: List<JsonObject>,
        rootName: String = "Shared",
        truncated: Boolean = false,
    ) = CommandResultPayload(
        requestId = "files-analyze-result-bounds",
        ok = true,
        result = buildJsonObject {
            put("rootName", rootName)
            put(
                "pathSegments",
                buildJsonArray { pathSegments.forEach { add(JsonPrimitive(it)) } },
            )
            put("scannedEntries", files.size)
            put("fileCount", files.size)
            put("directoryCount", 0)
            put("totalBytes", files.size.toLong())
            put("truncated", truncated)
            put("largestFiles", JsonArray(files))
        },
    )

    private fun largeFile(
        name: String,
        pathSegments: List<String>,
        mimeType: String,
    ) = buildJsonObject {
        put("name", name)
        put(
            "pathSegments",
            buildJsonArray { pathSegments.forEach { add(JsonPrimitive(it)) } },
        )
        put("mimeType", mimeType)
        put("sizeBytes", 123L)
        put("lastModifiedEpochMillis", 456L)
    }
}
