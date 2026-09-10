package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.protocol.CommandResultPayload
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Keeps files.list useful when exact SAF identities would otherwise make the remote result exceed
 * the shared command-result budget. Identity fields are never rewritten: invalid/inconsistent
 * identities are dropped, display-only text is bounded, and the largest prefix that fits the real
 * kotlinx.serialization payload encoding is kept.
 */
internal fun projectFilesListForRemoteResult(
    result: CommandResultPayload,
): CommandResultPayload {
    if (!result.ok) return result
    val source = result.result ?: return result
    val sourceEntries = source["entries"] as? JsonArray ?: return result
    val parentPath = remotePathSegments(source["pathSegments"]) ?: return result
    if (runCatching { AndroidSafFilesRepository.validatePathSegments(parentPath) }.isFailure) {
        return result
    }
    val remoteEntries = sourceEntries.mapNotNull { entry ->
        boundRemoteFileEntry(entry, parentPath)
    }

    fun candidate(returnedCount: Int): CommandResultPayload {
        val projected = source.toMutableMap()
        val rootName = projected["rootName"] as? JsonPrimitive
        if (rootName?.isString == true) {
            projected["rootName"] = JsonPrimitive(
                boundedRemoteText(rootName.content, MAX_REMOTE_FILE_ROOT_NAME_CHARS)
            )
        }
        projected["count"] = JsonPrimitive(returnedCount)
        projected["totalListedCount"] = JsonPrimitive(sourceEntries.size)
        projected["truncated"] = JsonPrimitive(returnedCount < sourceEntries.size)
        projected["entries"] = JsonArray(remoteEntries.take(returnedCount))
        return result.copy(result = JsonObject(projected))
    }

    var low = 0
    var high = remoteEntries.size
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

private fun boundRemoteFileEntry(
    entry: JsonElement,
    parentPath: List<String>,
): JsonElement? {
    val source = entry as? JsonObject ?: return null
    val name = (source["name"] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?: return null
    val pathSegments = remotePathSegments(source["pathSegments"]) ?: return null
    if (pathSegments != parentPath + name) return null
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

private fun remotePathSegments(value: JsonElement?): List<String>? {
    val array = value as? JsonArray ?: return null
    return array.map { element ->
        val primitive = element as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        primitive.content
    }
}

internal const val MAX_REMOTE_FILE_ROOT_NAME_CHARS = 120
internal const val MAX_REMOTE_FILE_MIME_TYPE_CHARS = 120
