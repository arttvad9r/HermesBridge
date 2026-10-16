package io.github.arttvad9r.typeddroid

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface RawShellBackend {
    fun readiness(): PrivilegedBackendReadiness
    suspend fun execute(command: String, timeoutMillis: Long): RawShellExecutionResult
}

data class RawShellExecutionResult(
    val ok: Boolean,
    val exitCode: Int? = null,
    val stdout: ByteArray = ByteArray(0),
    val stderr: ByteArray = ByteArray(0),
    val stdoutTruncated: Boolean = false,
    val stderrTruncated: Boolean = false,
    val code: String? = null,
    val message: String? = null,
)

object DisabledRawShellBackend : RawShellBackend {
    override fun readiness() = PrivilegedBackendReadiness(
        ready = false,
        code = "embedded_privileged_unavailable",
        message = "Embedded privileged helper has not been bootstrapped.",
    )

    override suspend fun execute(command: String, timeoutMillis: Long) = RawShellExecutionResult(
        ok = false,
        code = "embedded_privileged_unavailable",
        message = "Embedded privileged helper has not been bootstrapped.",
    )
}

class EmbeddedRawShellBackend(context: Context) : RawShellBackend {
    private val store = EmbeddedPrivilegedStore(context)

    override fun readiness(): PrivilegedBackendReadiness {
        val endpoint = store.endpoint()
            ?: return PrivilegedBackendReadiness(
                ready = false,
                code = "embedded_privileged_unavailable",
                message = "Enable full device access in TypedDroid before using raw shell.",
            )
        val ready = EmbeddedPrivilegedClient(endpoint).ping()
        if (!ready) runCatching(store::clear)
        return if (ready) {
            PrivilegedBackendReadiness(ready = true)
        } else {
            PrivilegedBackendReadiness(
                ready = false,
                code = "embedded_privileged_unavailable",
                message = "The embedded privileged helper is no longer running; restore full device access on the phone.",
            )
        }
    }

    override suspend fun execute(command: String, timeoutMillis: Long): RawShellExecutionResult =
        withContext(Dispatchers.IO) {
            val endpoint = store.endpoint()
                ?: return@withContext DisabledRawShellBackend.execute(command, timeoutMillis)
            try {
                val result = EmbeddedPrivilegedClient(endpoint).exec(command, timeoutMillis)
                RawShellExecutionResult(
                    ok = true,
                    exitCode = result.exitCode,
                    stdout = result.stdout,
                    stderr = result.stderr,
                    stdoutTruncated = result.stdoutTruncated,
                    stderrTruncated = result.stderrTruncated,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: EmbeddedPrivilegedRemoteException) {
                RawShellExecutionResult(
                    ok = false,
                    code = if (error.timedOut) "shell_timeout" else "shell_failed",
                    message = (error.message ?: "Privileged shell command failed.").take(300),
                )
            } catch (error: Throwable) {
                runCatching(store::clear)
                RawShellExecutionResult(
                    ok = false,
                    code = "embedded_privileged_failed",
                    message = (error.message ?: error::class.java.simpleName).take(300),
                )
            }
        }
}
