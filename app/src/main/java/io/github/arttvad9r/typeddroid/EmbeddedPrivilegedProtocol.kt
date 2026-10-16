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
    val command: String? = null,
    val timeoutMillis: Long? = null,
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
)

internal object EmbeddedPrivilegedProtocol {
    const val OP_PING = "ping"
    const val OP_EXEC = "exec"
    const val OP_SHUTDOWN = "shutdown"
    const val MAX_FRAME_BYTES = 2 * 1024 * 1024
    const val MAX_COMMAND_BYTES = 64 * 1024
    const val MAX_TIMEOUT_MILLIS = 5 * 60 * 1000L

    val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
        encodeDefaults = true
    }

    fun writeRequest(output: OutputStream, request: EmbeddedPrivilegedRequest) =
        writeFrame(output, json.encodeToString(request).encodeToByteArray())

    fun readRequest(input: InputStream): EmbeddedPrivilegedRequest =
        json.decodeFromString(readFrame(input).decodeToString(throwOnInvalidSequence = true))

    fun writeResponse(output: OutputStream, response: EmbeddedPrivilegedResponse) =
        writeFrame(output, json.encodeToString(response).encodeToByteArray())

    fun readResponse(input: InputStream): EmbeddedPrivilegedResponse =
        json.decodeFromString(readFrame(input).decodeToString(throwOnInvalidSequence = true))

    private fun writeFrame(output: OutputStream, payload: ByteArray) {
        require(payload.size in 1..MAX_FRAME_BYTES) { "Embedded privileged frame size is invalid." }
        val data = DataOutputStream(output)
        data.writeInt(payload.size)
        data.write(payload)
        data.flush()
    }

    private fun readFrame(input: InputStream): ByteArray {
        val data = DataInputStream(input)
        val size = data.readInt()
        require(size in 1..MAX_FRAME_BYTES) { "Embedded privileged frame size is invalid." }
        return ByteArray(size).also(data::readFully)
    }
}
