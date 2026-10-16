package io.github.arttvad9r.typeddroid

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import java.util.UUID

internal class EmbeddedPrivilegedRemoteException(
    message: String,
    val timedOut: Boolean,
) : IllegalStateException(message)

internal data class EmbeddedPrivilegedExecResult(
    val exitCode: Int,
    val stdout: ByteArray,
    val stderr: ByteArray,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
)

internal class EmbeddedPrivilegedClient(
    private val endpoint: EmbeddedPrivilegedEndpoint,
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
        response.requireRemoteSuccess("Embedded privileged command failed.")
        return response.toExecResult()
    }

    fun installApk(artifact: VerifiedApkArtifact, replace: Boolean): EmbeddedPrivilegedExecResult {
        require(artifact.file.isFile && artifact.sizeBytes in 1..MAX_APK_ARTIFACT_BYTES) { "APK artifact is invalid." }
        require(artifact.file.length() == artifact.sizeBytes) { "APK artifact size changed before upload." }
        openSocket(APK_RPC_TIMEOUT_MILLIS).use { socket ->
            val id = UUID.randomUUID().toString()
            EmbeddedPrivilegedProtocol.writeRequest(
                socket.outputStream,
                EmbeddedPrivilegedRequest(
                    id = id,
                    operation = EmbeddedPrivilegedProtocol.OP_INSTALL_APK,
                    authToken = endpoint.authToken,
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
                authToken = endpoint.authToken,
                packageName = packageName,
                keepData = keepData,
            )
        )

    fun forceStop(packageName: String): EmbeddedPrivilegedExecResult =
        typedRequest(
            EmbeddedPrivilegedRequest(
                id = UUID.randomUUID().toString(),
                operation = EmbeddedPrivilegedProtocol.OP_FORCE_STOP_APP,
                authToken = endpoint.authToken,
                packageName = packageName,
            )
        )

    fun revokePermission(packageName: String, permissionName: String, userId: Int): EmbeddedPrivilegedExecResult =
        typedRequest(
            EmbeddedPrivilegedRequest(
                id = UUID.randomUUID().toString(),
                operation = EmbeddedPrivilegedProtocol.OP_REVOKE_PERMISSION,
                authToken = endpoint.authToken,
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
                authToken = endpoint.authToken,
            ),
            timeoutMillis = BATTERY_RPC_TIMEOUT_MILLIS,
        )

    fun shutdown(): Boolean = runCatching {
        request(EmbeddedPrivilegedProtocol.OP_SHUTDOWN).ok
    }.getOrDefault(false)

    private fun typedRequest(
        request: EmbeddedPrivilegedRequest,
        timeoutMillis: Long = TYPED_RPC_TIMEOUT_MILLIS,
    ): EmbeddedPrivilegedExecResult {
        openSocket(timeoutMillis).use { socket ->
            EmbeddedPrivilegedProtocol.writeRequest(socket.outputStream, request)
            return decodeExecResponse(request.id, EmbeddedPrivilegedProtocol.readResponse(socket.inputStream))
        }
    }

    private fun request(
        operation: String,
        command: String? = null,
        timeoutMillis: Long? = null,
    ): EmbeddedPrivilegedResponse {
        val responseTimeout = ((timeoutMillis ?: DEFAULT_RPC_TIMEOUT_MILLIS) + RPC_TIMEOUT_GRACE_MILLIS)
            .coerceAtMost(EmbeddedPrivilegedProtocol.MAX_TIMEOUT_MILLIS + RPC_TIMEOUT_GRACE_MILLIS)
        openSocket(responseTimeout).use { socket ->
            val id = UUID.randomUUID().toString()
            EmbeddedPrivilegedProtocol.writeRequest(
                socket.outputStream,
                EmbeddedPrivilegedRequest(
                    id = id,
                    operation = operation,
                    authToken = endpoint.authToken,
                    command = command,
                    timeoutMillis = timeoutMillis,
                ),
            )
            val response = EmbeddedPrivilegedProtocol.readResponse(socket.inputStream)
            check(response.id == id) { "Mismatched privileged RPC response ID." }
            return response
        }
    }

    private fun openSocket(timeoutMillis: Long): Socket {
        val socket = Socket()
        try {
            val timeout = timeoutMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), endpoint.port), minOf(timeout, CONNECT_TIMEOUT_MILLIS))
            socket.soTimeout = timeout
            socket.tcpNoDelay = true
            return socket
        } catch (error: Throwable) {
            runCatching { socket.close() }
            throw error
        }
    }

    private fun decodeExecResponse(id: String, response: EmbeddedPrivilegedResponse): EmbeddedPrivilegedExecResult {
        check(response.id == id) { "Mismatched privileged RPC response ID." }
        response.requireRemoteSuccess("Embedded privileged operation failed.")
        return response.toExecResult()
    }

    private fun EmbeddedPrivilegedResponse.requireRemoteSuccess(defaultMessage: String) {
        if (!ok) {
            throw EmbeddedPrivilegedRemoteException(
                message = error ?: defaultMessage,
                timedOut = timedOut,
            )
        }
    }

    private fun EmbeddedPrivilegedResponse.toExecResult() = EmbeddedPrivilegedExecResult(
        exitCode = checkNotNull(exitCode),
        stdout = Base64.getDecoder().decode(stdoutBase64 ?: ""),
        stderr = Base64.getDecoder().decode(stderrBase64 ?: ""),
        stdoutTruncated = stdoutTruncated,
        stderrTruncated = stderrTruncated,
    )

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 2_000
        const val DEFAULT_RPC_TIMEOUT_MILLIS = 5_000L
        const val RPC_TIMEOUT_GRACE_MILLIS = 5_000L
        const val TYPED_RPC_TIMEOUT_MILLIS = 45_000L
        const val BATTERY_RPC_TIMEOUT_MILLIS = 45_000L
        const val APK_RPC_TIMEOUT_MILLIS = 6 * 60 * 1000L
    }
}
