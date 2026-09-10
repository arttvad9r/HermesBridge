package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.protocol.CommandResultPayload
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Keeps files.analyze useful when exact recursive SAF identities would otherwise make the remote
 * result exceed the shared command-result budget. The locally computed aggregate counters remain
 * intact; only the bounded largest-files detail list is projected for the remote control channel.
 */
internal fun projectFilesAnalyzeForRemoteResult(
    result: CommandResultPayload,
): CommandResultPayload {
    if (!result.ok) return result
    val source = result.result ?: return result
    val sourceFiles = source["largestFiles"] as? JsonArray ?: return result
    val rootPath = remotePathSegments(source["pathSegments"]) ?: return result
    if (runCatching { AndroidSafFilesRepository.validatePathSegments(rootPath) }.isFailure) {
        return result
    }
    val scanTruncated = (source["truncated"] as? JsonPrimitive)?.booleanOrNull ?: return result
    val remoteFiles = sourceFiles.mapNotNull { file ->
        boundRemoteAnalyzedFile(file, rootPath)
    }

    fun candidate(returnedCount: Int): CommandResultPayload {
        val projected = source.toMutableMap()
        val rootName = projected["rootName"] as? JsonPrimitive
        if (rootName?.isString == true) {
            projected["rootName"] = JsonPrimitive(
                boundedRemoteText(rootName.content, MAX_REMOTE_FILE_ROOT_NAME_CHARS)
            )
        }
        val resultTruncated = returnedCount < sourceFiles.size
        projected["scanTruncated"] = JsonPrimitive(scanTruncated)
        projected["resultTruncated"] = JsonPrimitive(resultTruncated)
        projected["truncated"] = JsonPrimitive(scanTruncated || resultTruncated)
        projected["totalLargestFileCount"] = JsonPrimitive(sourceFiles.size)
        projected["returnedLargestFileCount"] = JsonPrimitive(returnedCount)
        projected["largestFiles"] = JsonArray(remoteFiles.take(returnedCount))
        return result.copy(result = JsonObject(projected))
    }

    var low = 0
    var high = remoteFiles.size
    while (low < high) {
        val middle = (low + high + 1) / 2
        if (remoteCommandResultPayloadBytes(candidate(middle)) <= MAX_REMOTE_COMMAND_RESULT_PAYLOAD_BYTES) {
            low = middle
        } else {
            high = middle - 1
        }
    }
    return candidate(low)
}

private fun boundRemoteAnalyzedFile(
    file: JsonElement,
    rootPath: List<String>,
): JsonElement? {
    val source = file as? JsonObject ?: return null
    val name = (source["name"] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?: return null
    val pathSegments = remotePathSegments(source["pathSegments"]) ?: return null
    if (pathSegments.size <= rootPath.size) return null
    if (pathSegments.take(rootPath.size) != rootPath) return null
    if (pathSegments.last() != name) return null
    if (runCatching { AndroidSafFilesRepository.validatePathSegments(pathSegments) }.isFailure) {
        return null
    }

    val mimeType = source["mimeType"] as? JsonPrimitive ?: return source
    if (!mimeType.isString) return source
    val projected = source.toMutableMap()
    projected["mimeType"] = JsonPrimitive(
        boundedRemoteText(mimeType.content, MAX_REMOTE_FILE_MIME_TYPE_CHARS)
    )
    return JsonObject(projected)
}
