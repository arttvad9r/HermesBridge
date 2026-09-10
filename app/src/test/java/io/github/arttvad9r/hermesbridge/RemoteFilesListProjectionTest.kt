package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.protocol.BridgeProtocol
import io.github.arttvad9r.hermesbridge.protocol.CommandRequestPayload
import io.github.arttvad9r.hermesbridge.protocol.CommandResultPayload
import io.github.arttvad9r.hermesbridge.protocol.MessageType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
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

class RemoteFilesListProjectionTest {
    @Test
    fun exactFileIdentityIsPreservedWhileDisplayTextIsBounded() {
        val exactName = "report\n\u0800-final.txt"
        val source = fileListResult(
            pathSegments = listOf("Documents"),
            entries = listOf(
                fileEntry(
                    name = exactName,
                    pathSegments = listOf("Documents", exactName),
                    mimeType = "text/plain\n" + "m".repeat(300),
                )
            ),
            rootName = "Shared\n" + "r".repeat(300),
        )

        val projected = projectFilesListForRemoteResult(source)
        val entries = projected.result?.get("entries") as JsonArray
        val entry = entries.single()

        assertEquals(JsonPrimitive(exactName), entry.jsonObject["name"])
        assertEquals(
            buildJsonArray {
                add(JsonPrimitive("Documents"))
                add(JsonPrimitive(exactName))
            },
            entry.jsonObject["pathSegments"],
        )
        val rootName = projected.result?.get("rootName")?.jsonPrimitive?.content.orEmpty()
        val mimeType = entry.jsonObject["mimeType"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(rootName.length <= MAX_REMOTE_FILE_ROOT_NAME_CHARS)
        assertTrue(mimeType.length <= MAX_REMOTE_FILE_MIME_TYPE_CHARS)
        assertFalse(rootName.any(Char::isISOControl))
        assertFalse(mimeType.any(Char::isISOControl))
        assertEquals("1", projected.result?.get("count")?.toString())
        assertEquals("1", projected.result?.get("totalListedCount")?.toString())
        assertEquals("false", projected.result?.get("truncated")?.toString())
    }

    @Test
    fun malformedFileIdentityIsDroppedInsteadOfRewritten() {
        val source = fileListResult(
            pathSegments = listOf("Documents"),
            entries = listOf(
                fileEntry(
                    name = "valid.txt",
                    pathSegments = listOf("Documents", "valid.txt"),
                    mimeType = "text/plain",
                ),
                fileEntry(
                    name = "..",
                    pathSegments = listOf("Documents", ".."),
                    mimeType = "text/plain",
                ),
            ),
        )

        val projected = projectFilesListForRemoteResult(source)
        val entries = projected.result?.get("entries") as JsonArray

        assertEquals(1, entries.size)
        assertEquals(JsonPrimitive("valid.txt"), entries.single().jsonObject["name"])
        assertEquals("1", projected.result?.get("count")?.toString())
        assertEquals("2", projected.result?.get("totalListedCount")?.toString())
        assertEquals("true", projected.result?.get("truncated")?.toString())
    }

    @Test
    fun routerTruncatesWorstCaseExactPathsBeforeGenericSizeFailure() = runBlocking {
        val segment = "\u0800".repeat(255)
        val parentPath = List(31) { segment }
        val snapshots = (0 until 40).map { index ->
            val suffix = index.toString().padStart(2, '0')
            val name = "f$suffix-" + "\u0800".repeat(251)
            SafFileEntrySnapshot(
                name = name,
                pathSegments = parentPath + name,
                directory = false,
                mimeType = "application/" + "\u0800".repeat(300),
                sizeBytes = Long.MAX_VALUE,
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
                        rootName = "Shared\n" + "r".repeat(300),
                        pathSegments = pathSegments,
                        entries = snapshots,
                    )
                },
            ),
            appsRepository = appsRepository,
            appPermissionsRepository = null,
        )

        val result = router.execute(
            CommandRequestPayload(
                tool = BridgeToolRegistry.FILES_LIST,
                requestId = "files-list-result-bounds",
                arguments = buildJsonObject {
                    put(
                        "pathSegments",
                        buildJsonArray { parentPath.forEach { add(JsonPrimitive(it)) } },
                    )
                },
            )
        )
        val returned = result.result?.get("entries") as JsonArray

        assertTrue(result.ok)
        assertTrue(returned.isNotEmpty())
        assertTrue(returned.size < snapshots.size)
        assertEquals(returned.size.toString(), result.result?.get("count")?.toString())
        assertEquals(snapshots.size.toString(), result.result?.get("totalListedCount")?.toString())
        assertEquals("true", result.result?.get("truncated")?.toString())

        val payloadBytes = BridgeProtocol.json
            .encodeToString(result)
            .toByteArray(Charsets.UTF_8)
            .size
            .toLong()
        assertTrue(payloadBytes <= MAX_REMOTE_COMMAND_RESULT_PAYLOAD_BYTES)

        val envelopeBytes = BridgeProtocol.encode(
            BridgeProtocol.envelope(
                type = MessageType.COMMAND_RESULT,
                deviceId = "device_" + "d".repeat(80),
                payload = BridgeProtocol.payload(result),
            )
        ).toByteArray(Charsets.UTF_8).size.toLong()
        assertTrue(envelopeBytes < RELAY_WEBSOCKET_MAX_FRAME_BYTES)
    }

    private fun fileListResult(
        pathSegments: List<String>,
        entries: List<JsonObject>,
        rootName: String = "Shared",
    ) = CommandResultPayload(
        requestId = "files-list-result-bounds",
        ok = true,
        result = buildJsonObject {
            put("rootName", rootName)
            put(
                "pathSegments",
                buildJsonArray { pathSegments.forEach { add(JsonPrimitive(it)) } },
            )
            put("count", entries.size)
            put("entries", JsonArray(entries))
        },
    )

    private fun fileEntry(
        name: String,
        pathSegments: List<String>,
        mimeType: String,
    ) = buildJsonObject {
        put("name", name)
        put(
            "pathSegments",
            buildJsonArray { pathSegments.forEach { add(JsonPrimitive(it)) } },
        )
        put("directory", false)
        put("mimeType", mimeType)
        put("sizeBytes", Long.MAX_VALUE)
        put("lastModifiedEpochMillis", Long.MAX_VALUE)
    }
}
