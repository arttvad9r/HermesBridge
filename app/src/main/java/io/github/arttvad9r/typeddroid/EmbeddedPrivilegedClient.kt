package io.github.arttvad9r.typeddroid

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import java.io.Closeable
import java.util.Base64
import java.util.UUID

internal data class EmbeddedPrivilegedExecResult(
    val exitCode: Int,
    val stdout: ByteArray,
    val stderr: ByteArray,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
)

internal class EmbeddedPrivilegedClient(
    private val socketName: String,
) {
    fun ping(): Boolean = runCatching {
        request(EmbeddedPrivilegedProtocol.OP_PING).ok
    }.getOrDefault(false)

    fun exec(
        command: String,
        timeoutMillis: Long = 30_000L,
    ): EmbeddedPrivilegedExecResult {
        val response = request(
            operation = EmbeddedPrivilegedProtocol.OP_EXEC,
            command = command,
            timeoutMillis = timeoutMillis,
        )
        check(response.ok) { response.error ?: "Embedded privileged command failed." }
        return EmbeddedPrivilegedExecResult(
            exitCode = checkNotNull(response.exitCode),
            stdout = Base64.getDecoder().decode(response.stdoutBase64 ?: ""),
            stderr = Base64.getDecoder().decode(response.stderrBase64 ?: ""),
            stdoutTruncated = response.stdoutTruncated,
            stderrTruncated = response.stderrTruncated,
        )
    }

    fun shutdown(): Boolean = runCatching {
        request(EmbeddedPrivilegedProtocol.OP_SHUTDOWN).ok
    }.getOrDefault(false)

    private fun request(
        operation: String,
        command: String? = null,
        timeoutMillis: Long? = null,
    ): EmbeddedPrivilegedResponse {
        val socket = LocalSocket()
        socket.use {
            socket.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
            val responseTimeout = ((timeoutMillis ?: DEFAULT_RPC_TIMEOUT_MILLIS) + RPC_TIMEOUT_GRACE_MILLIS)
                .coerceAtMost(EmbeddedPrivilegedProtocol.MAX_TIMEOUT_MILLIS + RPC_TIMEOUT_GRACE_MILLIS)
            socket.soTimeout = responseTimeout.toInt()
            check(socket.peerCredentials.uid == Process.SHELL_UID) {
                "TypedDroid privileged socket peer is not Android shell UID."
            }
            val id = UUID.randomUUID().toString()
            EmbeddedPrivilegedProtocol.writeRequest(
                socket.outputStream,
                EmbeddedPrivilegedRequest(
                    id = id,
                    operation = operation,
                    command = command,
                    timeoutMillis = timeoutMillis,
                ),
            )
            val response = EmbeddedPrivilegedProtocol.readResponse(socket.inputStream)
            check(response.id == id) { "Mismatched privileged RPC response ID." }
            return response
        }
    }

    private companion object {
        const val DEFAULT_RPC_TIMEOUT_MILLIS = 5_000L
        const val RPC_TIMEOUT_GRACE_MILLIS = 5_000L
    }
}
