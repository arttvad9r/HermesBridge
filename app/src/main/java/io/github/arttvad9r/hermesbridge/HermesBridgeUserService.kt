package io.github.arttvad9r.hermesbridge

import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Narrow Shizuku UserService implementation.
 *
 * The Binder API intentionally exposes only fixed, typed operations. Agent input
 * is validated before a command is constructed, and no generic shell/argv entry
 * point exists across the process boundary.
 */
class HermesBridgeUserService() : IPrivilegedBridgeService.Stub() {
    override fun destroy() {
        exitProcess(0)
    }

    override fun installApk(
        apk: ParcelFileDescriptor,
        sizeBytes: Long,
        replace: Boolean,
    ): Bundle {
        if (sizeBytes !in 1..MAX_APK_ARTIFACT_BYTES) {
            return failure(
                code = "invalid_apk_artifact",
                message = "Verified APK size is outside the supported range.",
            )
        }

        return ParcelFileDescriptor.AutoCloseInputStream(apk).use { input ->
            val command = executeFixedCommand(
                argv = buildPmInstallCommand(sizeBytes, replace),
                timeoutMillis = INSTALL_TIMEOUT_MILLIS,
                stdin = input,
            )
            when {
                command.timedOut -> failure(
                    code = "install_timeout",
                    message = "Package manager did not finish installation within ${INSTALL_TIMEOUT_MILLIS / 1000} seconds.",
                )

                command.exitCode == 0 && command.stdout.contains("Success", ignoreCase = true) -> success()
                else -> failure(
                    code = "install_failed",
                    message = command.firstOutputLine() ?: "Package manager rejected the APK installation.",
                )
            }
        }
    }

    override fun uninstallApp(packageName: String, keepData: Boolean): Bundle {
        if (!isValidAndroidQualifiedName(packageName)) {
            return failure("invalid_package_name", "The package name is invalid.")
        }
        val command = executeFixedCommand(
            argv = buildPmUninstallCommand(packageName, keepData),
            timeoutMillis = EXEC_TIMEOUT_MILLIS,
        )
        return when {
            command.timedOut -> failure(
                "uninstall_timeout",
                "Package manager did not finish within ${EXEC_TIMEOUT_MILLIS / 1000} seconds.",
            )

            command.exitCode == 0 && command.stdout.contains("Success", ignoreCase = true) -> success()
            else -> failure(
                "uninstall_failed",
                command.firstOutputLine() ?: "Package manager rejected the uninstall request.",
            )
        }
    }

    override fun forceStopApp(packageName: String): Bundle {
        if (!isValidAndroidQualifiedName(packageName)) {
            return failure("invalid_package_name", "The package name is invalid.")
        }
        val command = executeFixedCommand(
            argv = buildAmForceStopCommand(packageName),
            timeoutMillis = EXEC_TIMEOUT_MILLIS,
        )
        return when {
            command.timedOut -> failure(
                "force_stop_timeout",
                "Activity manager did not finish within ${EXEC_TIMEOUT_MILLIS / 1000} seconds.",
            )

            command.exitCode == 0 -> success()
            else -> failure(
                "force_stop_failed",
                command.firstOutputLine() ?: "Activity manager rejected the force-stop request.",
            )
        }
    }

    override fun revokePermission(
        packageName: String,
        permissionName: String,
        userId: Int,
    ): Bundle {
        if (!isValidAndroidQualifiedName(packageName)) {
            return failure("invalid_package_name", "The package name is invalid.")
        }
        if (!isValidAndroidQualifiedName(permissionName)) {
            return failure("invalid_permission_name", "The permission name is invalid.")
        }
        if (!isSupportedAndroidUserId(userId)) {
            return failure("invalid_user_id", "Android user ID is outside the supported range.")
        }

        val command = executeFixedCommand(
            argv = buildPmRevokePermissionCommand(packageName, permissionName, userId),
            timeoutMillis = EXEC_TIMEOUT_MILLIS,
        )
        return when {
            command.timedOut -> failure(
                "permission_revoke_timeout",
                "Package manager did not finish permission revocation within ${EXEC_TIMEOUT_MILLIS / 1000} seconds.",
            )

            command.exitCode == 0 -> success()
            else -> failure(
                "permission_revoke_failed",
                command.firstOutputLine() ?: "Package manager rejected the permission revoke request.",
            )
        }
    }

    override fun writeBatteryStats(output: ParcelFileDescriptor): Bundle {
        return try {
            ParcelFileDescriptor.AutoCloseOutputStream(output).use { stdout ->
                val command = executeStreamingCommand(
                    argv = buildBatteryStatsCommand(),
                    timeoutMillis = BATTERY_STATS_TIMEOUT_MILLIS,
                    stdout = stdout,
                )
                when {
                    command.timedOut -> failure(
                        code = "battery_stats_timeout",
                        message = "Battery diagnostics did not finish within ${BATTERY_STATS_TIMEOUT_MILLIS / 1000} seconds.",
                    )

                    command.streamFailed -> failure(
                        code = "battery_stats_stream_failed",
                        message = command.firstOutputLine() ?: "Battery diagnostics output stream failed.",
                    )

                    command.exitCode == 0 -> success()
                    else -> failure(
                        code = "battery_stats_failed",
                        message = command.firstOutputLine() ?: "dumpsys batterystats failed.",
                    )
                }
            }
        } catch (error: Throwable) {
            failure(
                code = "battery_stats_stream_failed",
                message = safeErrorMessage(error),
            )
        }
    }

    private fun executeFixedCommand(
        argv: Array<String>,
        timeoutMillis: Long,
        stdin: InputStream? = null,
    ): ServiceCommandResult {
        val process = try {
            ProcessBuilder(*argv).start()
        } catch (error: Throwable) {
            return ServiceCommandResult(
                exitCode = null,
                stdout = "",
                stderr = safeErrorMessage(error),
            )
        }

        val executor = Executors.newFixedThreadPool(if (stdin == null) 2 else 3)
        return try {
            if (stdin == null) runCatching { process.outputStream.close() }
            val stdoutFuture = executor.submit<String> {
                process.inputStream.use { readBoundedText(it, MAX_COMMAND_OUTPUT_BYTES) }
            }
            val stderrFuture = executor.submit<String> {
                process.errorStream.use { readBoundedText(it, MAX_COMMAND_OUTPUT_BYTES) }
            }
            val stdinFuture = stdin?.let { source ->
                executor.submit<Unit> {
                    process.outputStream.use { output -> source.copyTo(output) }
                }
            }

            val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            if (!finished) {
                runCatching { process.destroyForcibly() }
                stdinFuture?.cancel(true)
                stdoutFuture.cancel(true)
                stderrFuture.cancel(true)
                ServiceCommandResult(
                    exitCode = null,
                    stdout = "",
                    stderr = "",
                    timedOut = true,
                )
            } else {
                // Package manager may close stdin early on rejection. Its own exit
                // status/output remains authoritative, so a broken pipe is ignored.
                runCatching { stdinFuture?.get() }
                ServiceCommandResult(
                    exitCode = process.exitValue(),
                    stdout = runCatching { stdoutFuture.get() }.getOrDefault(""),
                    stderr = runCatching { stderrFuture.get() }.getOrDefault(""),
                )
            }
        } catch (error: Throwable) {
            runCatching { process.destroyForcibly() }
            ServiceCommandResult(
                exitCode = null,
                stdout = "",
                stderr = safeErrorMessage(error),
            )
        } finally {
            executor.shutdownNow()
        }
    }

    private fun executeStreamingCommand(
        argv: Array<String>,
        timeoutMillis: Long,
        stdout: OutputStream,
    ): ServiceCommandResult {
        val process = try {
            ProcessBuilder(*argv).start()
        } catch (error: Throwable) {
            return ServiceCommandResult(
                exitCode = null,
                stdout = "",
                stderr = safeErrorMessage(error),
            )
        }

        val executor = Executors.newFixedThreadPool(2)
        return try {
            runCatching { process.outputStream.close() }
            val stdoutFuture = executor.submit<Unit> {
                process.inputStream.use { input ->
                    val buffer = ByteArray(STREAM_BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        stdout.write(buffer, 0, read)
                    }
                    stdout.flush()
                }
            }
            val stderrFuture = executor.submit<String> {
                process.errorStream.use { readBoundedText(it, MAX_COMMAND_OUTPUT_BYTES) }
            }

            val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            if (!finished) {
                runCatching { process.destroyForcibly() }
                stdoutFuture.cancel(true)
                stderrFuture.cancel(true)
                ServiceCommandResult(
                    exitCode = null,
                    stdout = "",
                    stderr = "",
                    timedOut = true,
                )
            } else {
                val streamFailure = runCatching { stdoutFuture.get() }.exceptionOrNull()
                val stderr = runCatching { stderrFuture.get() }.getOrDefault("")
                ServiceCommandResult(
                    exitCode = process.exitValue(),
                    stdout = "",
                    stderr = streamFailure?.let(::safeErrorMessage) ?: stderr,
                    streamFailed = streamFailure != null,
                )
            }
        } catch (error: Throwable) {
            runCatching { process.destroyForcibly() }
            ServiceCommandResult(
                exitCode = null,
                stdout = "",
                stderr = safeErrorMessage(error),
                streamFailed = true,
            )
        } finally {
            executor.shutdownNow()
        }
    }

    private fun readBoundedText(input: InputStream, limitBytes: Int): String {
        val output = ByteArrayOutputStream(minOf(limitBytes, 16 * 1024))
        val buffer = ByteArray(8 * 1024)
        var retained = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (retained < limitBytes) {
                val accepted = minOf(read, limitBytes - retained)
                output.write(buffer, 0, accepted)
                retained += accepted
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun safeErrorMessage(error: Throwable): String {
        val source = error.cause ?: error
        return (source.message ?: source::class.java.simpleName).take(MAX_MESSAGE_LENGTH)
    }

    private fun success() = Bundle().apply {
        putBoolean(PrivilegedUserServiceProtocol.KEY_OK, true)
    }

    private fun failure(code: String, message: String) = Bundle().apply {
        putBoolean(PrivilegedUserServiceProtocol.KEY_OK, false)
        putString(PrivilegedUserServiceProtocol.KEY_CODE, code)
        putString(PrivilegedUserServiceProtocol.KEY_MESSAGE, message.take(MAX_MESSAGE_LENGTH))
    }

    private data class ServiceCommandResult(
        val exitCode: Int?,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean = false,
        val streamFailed: Boolean = false,
    ) {
        fun firstOutputLine(): String? = sequenceOf(stderr, stdout)
            .flatMap { it.lineSequence() }
            .map(String::trim)
            .firstOrNull(String::isNotEmpty)
            ?.take(MAX_MESSAGE_LENGTH)
    }

    private companion object {
        const val EXEC_TIMEOUT_MILLIS = 30_000L
        const val INSTALL_TIMEOUT_MILLIS = 180_000L
        const val BATTERY_STATS_TIMEOUT_MILLIS = 45_000L
        const val MAX_COMMAND_OUTPUT_BYTES = 64 * 1024
        const val STREAM_BUFFER_BYTES = 16 * 1024
        const val MAX_MESSAGE_LENGTH = 200
    }
}

internal object PrivilegedUserServiceProtocol {
    const val KEY_OK = "ok"
    const val KEY_CODE = "code"
    const val KEY_MESSAGE = "message"
}