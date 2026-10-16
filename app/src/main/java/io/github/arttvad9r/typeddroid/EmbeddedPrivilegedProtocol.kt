package io.github.arttvad9r.typeddroid

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class EmbeddedPrivilegedRequest(
    val id: String,
    val operation: String,
    val authToken: String,
    val command: String? = null,
    val timeoutMillis: Long? = null,
    val packageName: String? = null,
    val permissionName: String? = null,
    val userId: Int? = null,
    val keepData: Boolean? = null,
    val artifactSizeBytes: Long? = null,
    val artifactSha256: String? = null,
    val replace: Boolean? = null,
    val x: Int? = null,
    val y: Int? = null,
    val startX: Int? = null,
    val startY: Int? = null,
    val endX: Int? = null,
    val endY: Int? = null,
    val durationMillis: Int? = null,
    val keyCode: Int? = null,
    val text: String? = null,
)

@Serializable
internal data class EmbeddedPrivilegedResponse(
    val id: String,
    val ok: Boolean,
    val exitCode: Int? = null,
    val stdoutBase64: String? = null,
    val stderrBase64: String? = null,
    val stdoutTruncated: Boolean = false,
    val stderrTruncated: Boolean = false,
    val timedOut: Boolean = false,
    val error: String? = null,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
    val mimeType: String? = null,
)

internal object EmbeddedPrivilegedProtocol {
    const val OP_PING = "ping"
    const val OP_EXEC = "exec"
    const val OP_SHUTDOWN = "shutdown"
    const val OP_INSTALL_APK = "install_apk"
    const val OP_UNINSTALL_APP = "uninstall_app"
    const val OP_FORCE_STOP_APP = "force_stop_app"
    const val OP_REVOKE_PERMISSION = "revoke_permission"
    const val OP_BATTERY_STATS = "battery_stats"
    const val OP_UI_HIERARCHY = "ui_hierarchy"
    const val OP_UI_SCREENSHOT = "ui_screenshot"
    const val OP_UI_TAP = "ui_tap"
    const val OP_UI_SWIPE = "ui_swipe"
    const val OP_UI_TEXT = "ui_text"
    const val OP_UI_KEY = "ui_key"
    const val OP_OPEN_APP = "open_app"
    // Batterystats may retain up to 4 MiB before base64 encoding into the response frame.
    const val MAX_REQUEST_FRAME_BYTES = 128 * 1024
    const val MAX_RESPONSE_FRAME_BYTES = 8 * 1024 * 1024
    const val MAX_COMMAND_BYTES = 64 * 1024
    const val MAX_TIMEOUT_MILLIS = 5 * 60 * 1000L

    val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
        encodeDefaults = true
    }

    fun writeRequest(output: OutputStream, request: EmbeddedPrivilegedRequest) =
        writeFrame(output, json.encodeToString(request).encodeToByteArray(), MAX_REQUEST_FRAME_BYTES)

    fun readRequest(input: InputStream): EmbeddedPrivilegedRequest =
        json.decodeFromString(readFrame(input, MAX_REQUEST_FRAME_BYTES).decodeToString(throwOnInvalidSequence = true))

    fun writeResponse(output: OutputStream, response: EmbeddedPrivilegedResponse) =
        writeFrame(output, json.encodeToString(response).encodeToByteArray(), MAX_RESPONSE_FRAME_BYTES)

    fun readResponse(input: InputStream): EmbeddedPrivilegedResponse =
        json.decodeFromString(readFrame(input, MAX_RESPONSE_FRAME_BYTES).decodeToString(throwOnInvalidSequence = true))

    private fun writeFrame(output: OutputStream, payload: ByteArray, maxBytes: Int) {
        require(payload.size in 1..maxBytes) { "Embedded privileged frame size is invalid." }
        val data = DataOutputStream(output)
        data.writeInt(payload.size)
        data.write(payload)
        data.flush()
    }

    private fun readFrame(input: InputStream, maxBytes: Int): ByteArray {
        val data = DataInputStream(input)
        val size = data.readInt()
        require(size in 1..maxBytes) { "Embedded privileged frame size is invalid." }
        return ByteArray(size).also(data::readFully)
    }
}
