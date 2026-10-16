package io.github.arttvad9r.typeddroid

import android.net.Credentials
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Process
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * TypedDroid-owned privileged helper bootstrapped by the embedded ADB client.
 *
 * This process is expected to run as Android's shell UID. It accepts only the exact app UID
 * supplied by the bootstrap command over an abstract local socket. No TCP listener is created.
 */
object EmbeddedPrivilegedMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val config = parseArgs(args)
        check(Process.myUid() == Process.SHELL_UID) {
            "Embedded TypedDroid helper must run as Android shell UID."
        }

        LocalServerSocket(config.socketName).use { server ->
            while (true) {
                val client = server.accept()
                val shouldExit = try {
                    handleClient(client, config.appUid)
                } finally {
                    runCatching { client.close() }
                }
                if (shouldExit) break
            }
        }
        exitProcess(0)
    }

    private fun handleClient(socket: LocalSocket, expectedAppUid: Int): Boolean {
        val credentials: Credentials = socket.peerCredentials
        if (credentials.uid != expectedAppUid) return false

        val request = runCatching {
            EmbeddedPrivilegedProtocol.readRequest(socket.inputStream)
        }.getOrElse { error ->
            EmbeddedPrivilegedProtocol.writeResponse(
                socket.outputStream,
                EmbeddedPrivilegedResponse(
                    id = "invalid",
                    ok = false,
                    error = safeError(error),
                ),
            )
            return false
        }

        val response = when (request.operation) {
            EmbeddedPrivilegedProtocol.OP_PING -> EmbeddedPrivilegedResponse(
                id = request.id,
                ok = true,
            )

            EmbeddedPrivilegedProtocol.OP_EXEC -> execute(request)

            EmbeddedPrivilegedProtocol.OP_SHUTDOWN -> EmbeddedPrivilegedResponse(
                id = request.id,
                ok = true,
            )

            else -> EmbeddedPrivilegedResponse(
                id = request.id,
                ok = false,
                error = "Unknown privileged operation.",
            )
        }
        EmbeddedPrivilegedProtocol.writeResponse(socket.outputStream, response)
        return request.operation == EmbeddedPrivilegedProtocol.OP_SHUTDOWN && response.ok
    }

    private fun execute(request: EmbeddedPrivilegedRequest): EmbeddedPrivilegedResponse {
        val command = request.command
            ?: return EmbeddedPrivilegedResponse(request.id, false, error = "Shell command is missing.")
        if (command.isEmpty() || command.encodeToByteArray().size > EmbeddedPrivilegedProtocol.MAX_COMMAND_BYTES || '\u0000' in command) {
            return EmbeddedPrivilegedResponse(request.id, false, error = "Shell command is invalid.")
        }
        val timeout = request.timeoutMillis ?: DEFAULT_TIMEOUT_MILLIS
        if (timeout !in 1..EmbeddedPrivilegedProtocol.MAX_TIMEOUT_MILLIS) {
            return EmbeddedPrivilegedResponse(request.id, false, error = "Shell timeout is invalid.")
        }

        val process = try {
            ProcessBuilder("/system/bin/sh", "-c", command).start()
        } catch (error: Throwable) {
            return EmbeddedPrivilegedResponse(request.id, false, error = safeError(error))
        }
        runCatching { process.outputStream.close() }
        val executor = Executors.newFixedThreadPool(2)
        return try {
            val stdoutFuture = executor.submit<CapturedOutput> { process.inputStream.use(::readBounded) }
            val stderrFuture = executor.submit<CapturedOutput> { process.errorStream.use(::readBounded) }
            val finished = process.waitFor(timeout, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                stdoutFuture.cancel(true)
                stderrFuture.cancel(true)
                EmbeddedPrivilegedResponse(
                    id = request.id,
                    ok = false,
                    timedOut = true,
                    error = "Privileged command timed out.",
                )
            } else {
                val stdout = stdoutFuture.get()
                val stderr = stderrFuture.get()
                EmbeddedPrivilegedResponse(
                    id = request.id,
                    ok = true,
                    exitCode = process.exitValue(),
                    stdoutBase64 = Base64.getEncoder().encodeToString(stdout.bytes),
                    stderrBase64 = Base64.getEncoder().encodeToString(stderr.bytes),
                    stdoutTruncated = stdout.truncated,
                    stderrTruncated = stderr.truncated,
                )
            }
        } catch (error: Throwable) {
            runCatching { process.destroyForcibly() }
            EmbeddedPrivilegedResponse(request.id, false, error = safeError(error))
        } finally {
            executor.shutdownNow()
        }
    }

    private fun readBounded(input: java.io.InputStream): CapturedOutput {
        val output = ByteArrayOutputStream(MAX_RETAINED_STREAM_BYTES)
        val buffer = ByteArray(16 * 1024)
        var retained = 0
        var truncated = false
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            val keep = minOf(count, MAX_RETAINED_STREAM_BYTES - retained)
            if (keep > 0) {
                output.write(buffer, 0, keep)
                retained += keep
            }
            if (keep < count) truncated = true
        }
        return CapturedOutput(output.toByteArray(), truncated)
    }

    private data class CapturedOutput(
        val bytes: ByteArray,
        val truncated: Boolean,
    )

    private fun parseArgs(args: Array<String>): Config {
        require(args.size == 4 && args[0] == "--socket" && args[2] == "--app-uid") {
            "Invalid TypedDroid privileged helper arguments."
        }
        val socketName = args[1]
        require(SOCKET_NAME.matches(socketName)) { "Invalid privileged socket name." }
        val appUid = args[3].toIntOrNull()
            ?: throw IllegalArgumentException("Invalid app UID.")
        require(appUid >= Process.FIRST_APPLICATION_UID) { "Expected an Android application UID." }
        return Config(socketName, appUid)
    }

    private fun safeError(error: Throwable): String =
        ((error.cause ?: error).message ?: error::class.java.simpleName).take(300)

    private data class Config(val socketName: String, val appUid: Int)

    private const val DEFAULT_TIMEOUT_MILLIS = 30_000L
    // Two base64-encoded streams at this cap remain below the 2 MiB RPC frame limit.
    private const val MAX_RETAINED_STREAM_BYTES = 384 * 1024
    private const val MAX_COMMAND_OUTPUT_BYTES = 512 * 1024
    private val SOCKET_NAME = Regex("^typeddroid-[a-f0-9]{32}$")
}
