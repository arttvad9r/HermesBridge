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

    fun installApk(artifact: VerifiedApkArtifact, replace: Boolean): EmbeddedPrivilegedExecResult {
        require(artifact.file.isFile && artifact.sizeBytes in 1..MAX_APK_ARTIFACT_BYTES) { "APK artifact is invalid." }
        require(artifact.file.length() == artifact.sizeBytes) { "APK artifact size changed before upload." }
        val socket = openSocket(APK_RPC_TIMEOUT_MILLIS)
        socket.use {
            val id = UUID.randomUUID().toString()
            EmbeddedPrivilegedProtocol.writeRequest(
                socket.outputStream,
                EmbeddedPrivilegedRequest(
                    id = id,
                    operation = EmbeddedPrivilegedProtocol.OP_INSTALL_APK,
                    artifactSizeBytes = artifact.sizeBytes,
                    artifactSha256 = artifact.sha256,
                    replace = replace,
                ),
            )
            artifact.file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                var remaining = artifact.sizeBytes
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    check(read >= 0) { "APK artifact ended before its verified size." }
                    if (read == 0) continue
                    socket.outputStream.write(buffer, 0, read)
                    remaining -= read
                }
                socket.outputStream.flush()
            }
            return decodeExecResponse(id, EmbeddedPrivilegedProtocol.readResponse(socket.inputStream))
        }
    }

    fun uninstall(packageName: String, keepData: Boolean): EmbeddedPrivilegedExecResult =
        typedRequest(
            EmbeddedPrivilegedRequest(
                id = UUID.randomUUID().toString(),
                operation = EmbeddedPrivilegedProtocol.OP_UNINSTALL_APP,
                packageName = packageName,
                keepData = keepData,
            )
        )

    fun forceStop(packageName: String): EmbeddedPrivilegedExecResult =
        typedRequest(
            EmbeddedPrivilegedRequest(
                id = UUID.randomUUID().toString(),
                operation = EmbeddedPrivilegedProtocol.OP_FORCE_STOP_APP,
                packageName = packageName,
            )
        )

    fun revokePermission(packageName: String, permissionName: String, userId: Int): EmbeddedPrivilegedExecResult =
        typedRequest(
            EmbeddedPrivilegedRequest(
                id = UUID.randomUUID().toString(),
                operation = EmbeddedPrivilegedProtocol.OP_REVOKE_PERMISSION,
                packageName = packageName,
                permissionName = permissionName,
                userId = userId,
            )
        )

    fun batteryStats(): EmbeddedPrivilegedExecResult =
        typedRequest(
            EmbeddedPrivilegedRequest(
                id = UUID.randomUUID().toString(),
                operation = EmbeddedPrivilegedProtocol.OP_BATTERY_STATS,
            ),
            timeoutMillis = BATTERY_RPC_TIMEOUT_MILLIS,
        )

    private fun typedRequest(
        request: EmbeddedPrivilegedRequest,
        timeoutMillis: Long = TYPED_RPC_TIMEOUT_MILLIS,
    ): EmbeddedPrivilegedExecResult {
        val socket = openSocket(timeoutMillis)
        socket.use {
            EmbeddedPrivilegedProtocol.writeRequest(socket.outputStream, request)
            return decodeExecResponse(request.id, EmbeddedPrivilegedProtocol.readResponse(socket.inputStream))
        }
    }

    private fun decodeExecResponse(id: String, response: EmbeddedPrivilegedResponse): EmbeddedPrivilegedExecResult {
        check(response.id == id) { "Mismatched privileged RPC response ID." }
        check(response.ok) { response.error ?: "Embedded privileged operation failed." }
        return EmbeddedPrivilegedExecResult(
            exitCode = checkNotNull(response.exitCode),
            stdout = Base64.getDecoder().decode(response.stdoutBase64 ?: ""),
            stderr = Base64.getDecoder().decode(response.stderrBase64 ?: ""),
            stdoutTruncated = response.stdoutTruncated,
            stderrTruncated = response.stderrTruncated,
        )
    }

    private fun openSocket(timeoutMillis: Long): LocalSocket {
        val socket = LocalSocket()
        try {
            socket.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
            socket.soTimeout = timeoutMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            check(socket.peerCredentials.uid == Process.SHELL_UID) {
                "TypedDroid privileged socket peer is not Android shell UID."
            }
            return socket
        } catch (error: Throwable) {
            runCatching { socket.close() }
            throw error
        }
    }

    fun shutdown(): Boolean = runCatching {
        request(EmbeddedPrivilegedProtocol.OP_SHUTDOWN).ok
    }.getOrDefault(false)

    private fun request(
        operation: String,
        command: String? = null,
        timeoutMillis: Long? = null,
    ): EmbeddedPrivilegedResponse {
        val responseTimeout = ((timeoutMillis ?: DEFAULT_RPC_TIMEOUT_MILLIS) + RPC_TIMEOUT_GRACE_MILLIS)
            .coerceAtMost(EmbeddedPrivilegedProtocol.MAX_TIMEOUT_MILLIS + RPC_TIMEOUT_GRACE_MILLIS)
        val socket = openSocket(responseTimeout)
        socket.use {
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
        const val TYPED_RPC_TIMEOUT_MILLIS = 45_000L
        const val BATTERY_RPC_TIMEOUT_MILLIS = 45_000L
        const val APK_RPC_TIMEOUT_MILLIS = 6 * 60 * 1000L
    }
}
