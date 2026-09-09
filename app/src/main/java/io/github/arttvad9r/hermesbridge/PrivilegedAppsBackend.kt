package io.github.arttvad9r.hermesbridge

import android.content.pm.PackageManager
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationTargetException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

interface PrivilegedAppsBackend {
    fun readiness(): PrivilegedBackendReadiness

    suspend fun uninstall(
        packageName: String,
        keepData: Boolean,
    ): PrivilegedOperationResult

    suspend fun forceStop(packageName: String): PrivilegedOperationResult
}

data class PrivilegedBackendReadiness(
    val ready: Boolean,
    val code: String? = null,
    val message: String? = null,
)

data class PrivilegedOperationResult(
    val ok: Boolean,
    val code: String? = null,
    val message: String? = null,
)

/**
 * Narrow Shizuku package backend adapted from the Apache-2.0 droid-mcp project,
 * pinned to upstream commit aeaa5b9e8e96f56ef64a7ca23d0726585f7b1103 (0.10.1 era).
 *
 * Only fixed typed package operations are retained here. No generic command
 * execution method is exposed through Hermes Bridge.
 * See THIRD_PARTY_NOTICES.md for attribution.
 */
class DroidMcpShizukuAppsBackend : PrivilegedAppsBackend {
    override fun readiness(): PrivilegedBackendReadiness {
        val localState = ShizukuRuntime.state.value
        if (localState.status != ShizukuAccessStatus.READY) {
            return PrivilegedBackendReadiness(
                ready = false,
                code = "shizuku_unavailable",
                message = localState.message ?: "Shizuku is not ready for privileged app operations.",
            )
        }

        val binderReady = runCatching {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        return if (binderReady) {
            PrivilegedBackendReadiness(ready = true)
        } else {
            PrivilegedBackendReadiness(
                ready = false,
                code = "shizuku_unavailable",
                message = "Shizuku binder or permission is no longer available.",
            )
        }
    }

    override suspend fun uninstall(
        packageName: String,
        keepData: Boolean,
    ): PrivilegedOperationResult = withContext(Dispatchers.IO) {
        readinessFailure()?.let { return@withContext it }
        invalidPackageFailure(packageName)?.let { return@withContext it }

        val argv = buildList {
            add("pm")
            add("uninstall")
            if (keepData) add("-k")
            add(packageName)
        }.toTypedArray()

        when (
            val attempt = executeAttempt(
                argv = argv,
                timeoutCode = "uninstall_timeout",
                timeoutMessage = "Package manager did not finish within ${EXEC_TIMEOUT_MS / 1000} seconds.",
            )
        ) {
            is FixedCommandAttempt.Failure -> attempt.result
            is FixedCommandAttempt.Success -> {
                val command = attempt.result
                if (command.exitCode == 0 && command.stdout.contains("Success", ignoreCase = true)) {
                    PrivilegedOperationResult(ok = true)
                } else {
                    PrivilegedOperationResult(
                        ok = false,
                        code = "uninstall_failed",
                        message = command.firstOutputLine()
                            ?: "Package manager rejected the uninstall request.",
                    )
                }
            }
        }
    }

    override suspend fun forceStop(packageName: String): PrivilegedOperationResult =
        withContext(Dispatchers.IO) {
            readinessFailure()?.let { return@withContext it }
            invalidPackageFailure(packageName)?.let { return@withContext it }

            when (
                val attempt = executeAttempt(
                    argv = arrayOf("am", "force-stop", packageName),
                    timeoutCode = "force_stop_timeout",
                    timeoutMessage = "Activity manager did not finish within ${EXEC_TIMEOUT_MS / 1000} seconds.",
                )
            ) {
                is FixedCommandAttempt.Failure -> attempt.result
                is FixedCommandAttempt.Success -> {
                    val command = attempt.result
                    if (command.exitCode == 0) {
                        PrivilegedOperationResult(ok = true)
                    } else {
                        PrivilegedOperationResult(
                            ok = false,
                            code = "force_stop_failed",
                            message = command.firstOutputLine()
                                ?: "Activity manager rejected the force-stop request.",
                        )
                    }
                }
            }
        }

    private fun readinessFailure(): PrivilegedOperationResult? {
        val ready = readiness()
        return if (ready.ready) null else PrivilegedOperationResult(
            ok = false,
            code = ready.code,
            message = ready.message,
        )
    }

    private fun invalidPackageFailure(packageName: String): PrivilegedOperationResult? =
        if (PACKAGE_NAME_REGEX.matches(packageName) && packageName.length in 3..255) {
            null
        } else {
            PrivilegedOperationResult(
                ok = false,
                code = "invalid_package_name",
                message = "The package name is invalid.",
            )
        }

    private suspend fun executeAttempt(
        argv: Array<String>,
        timeoutCode: String,
        timeoutMessage: String,
    ): FixedCommandAttempt {
        return try {
            FixedCommandAttempt.Success(executeFixedCommand(argv))
        } catch (_: TimeoutCancellationException) {
            FixedCommandAttempt.Failure(
                PrivilegedOperationResult(
                    ok = false,
                    code = timeoutCode,
                    message = timeoutMessage,
                )
            )
        } catch (error: Throwable) {
            FixedCommandAttempt.Failure(
                PrivilegedOperationResult(
                    ok = false,
                    code = "shizuku_spawn_failed",
                    message = (error.message ?: error::class.java.simpleName).take(200),
                )
            )
        }
    }

    private suspend fun executeFixedCommand(argv: Array<String>): FixedCommandResult {
        if (!Shizuku.pingBinder()) {
            error("Shizuku binder is not reachable.")
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            error("Shizuku permission is not granted to Hermes Bridge.")
        }

        val process = spawnViaShizuku(argv)
        val stdoutBuffer = ByteArrayOutputStream()
        val stderrBuffer = ByteArrayOutputStream()

        return withTimeout(EXEC_TIMEOUT_MS) {
            coroutineScope {
                val stdoutJob = launch(Dispatchers.IO) {
                    runCatching { process.inputStream.use { it.copyTo(stdoutBuffer) } }
                }
                val stderrJob = launch(Dispatchers.IO) {
                    runCatching { process.errorStream.use { it.copyTo(stderrBuffer) } }
                }
                try {
                    val exitCode = runInterruptible(Dispatchers.IO) { process.waitFor() }
                    stdoutJob.join()
                    stderrJob.join()
                    FixedCommandResult(
                        exitCode = exitCode,
                        stdout = stdoutBuffer.toString(Charsets.UTF_8.name()),
                        stderr = stderrBuffer.toString(Charsets.UTF_8.name()),
                    )
                } finally {
                    runCatching { process.destroy() }
                }
            }
        }
    }

    private fun spawnViaShizuku(argv: Array<String>): Process {
        val method = newProcessMethod
            ?: error("Shizuku.newProcess is unavailable in the linked Shizuku API.")
        return try {
            method.invoke(null, argv, null, null) as Process
        } catch (error: InvocationTargetException) {
            val cause = error.targetException ?: error
            throw IllegalStateException(
                "Shizuku.newProcess failed: ${cause.message ?: cause::class.java.simpleName}",
                cause,
            )
        } catch (error: ReflectiveOperationException) {
            throw IllegalStateException("Shizuku.newProcess reflection failed: ${error.message}", error)
        }
    }

    private sealed interface FixedCommandAttempt {
        data class Success(val result: FixedCommandResult) : FixedCommandAttempt
        data class Failure(val result: PrivilegedOperationResult) : FixedCommandAttempt
    }

    private data class FixedCommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) {
        fun firstOutputLine(): String? = sequenceOf(stderr, stdout)
            .flatMap { it.lineSequence() }
            .map(String::trim)
            .firstOrNull(String::isNotEmpty)
            ?.take(200)
    }

    companion object {
        private const val EXEC_TIMEOUT_MS = 30_000L
        private val PACKAGE_NAME_REGEX = Regex(
            "^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$"
        )

        @Volatile
        private var cachedNewProcessMethod: java.lang.reflect.Method? = null

        private val newProcessMethod: java.lang.reflect.Method?
            get() = cachedNewProcessMethod ?: runCatching {
                Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java,
                ).apply { isAccessible = true }
            }.getOrNull().also { cachedNewProcessMethod = it }
    }
}

object DisabledPrivilegedAppsBackend : PrivilegedAppsBackend {
    override fun readiness() = PrivilegedBackendReadiness(
        ready = false,
        code = "shizuku_unavailable",
        message = "Privileged app backend is not configured.",
    )

    override suspend fun uninstall(
        packageName: String,
        keepData: Boolean,
    ) = unavailable()

    override suspend fun forceStop(packageName: String) = unavailable()

    private fun unavailable() = PrivilegedOperationResult(
        ok = false,
        code = "shizuku_unavailable",
        message = "Privileged app backend is not configured.",
    )
}
