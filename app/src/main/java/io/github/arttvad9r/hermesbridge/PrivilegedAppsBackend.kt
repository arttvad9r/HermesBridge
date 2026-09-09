package io.github.arttvad9r.hermesbridge

import io.droidmcp.shizuku.ShizukuShellBackend
import io.droidmcp.shell.UninstallAppTool

interface PrivilegedAppsBackend {
    fun readiness(): PrivilegedBackendReadiness

    suspend fun uninstall(
        packageName: String,
        keepData: Boolean,
    ): PrivilegedOperationResult
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

class DroidMcpShizukuAppsBackend : PrivilegedAppsBackend {
    private val uninstallTool = UninstallAppTool(ShizukuShellBackend())

    override fun readiness(): PrivilegedBackendReadiness {
        val state = ShizukuRuntime.state.value
        return if (state.status == ShizukuAccessStatus.READY) {
            PrivilegedBackendReadiness(ready = true)
        } else {
            PrivilegedBackendReadiness(
                ready = false,
                code = "shizuku_unavailable",
                message = state.message ?: "Shizuku is not ready for privileged app operations.",
            )
        }
    }

    override suspend fun uninstall(
        packageName: String,
        keepData: Boolean,
    ): PrivilegedOperationResult {
        val ready = readiness()
        if (!ready.ready) {
            return PrivilegedOperationResult(
                ok = false,
                code = ready.code,
                message = ready.message,
            )
        }

        val result = uninstallTool.execute(
            mapOf(
                "package_name" to packageName,
                "keep_data" to keepData,
            )
        )
        if (result.isSuccess) {
            return PrivilegedOperationResult(ok = true)
        }

        val error = result.errorMessage.orEmpty()
        val separator = error.indexOf(':')
        val code = if (separator > 0) error.substring(0, separator).trim() else "uninstall_failed"
        val detail = if (separator > 0) error.substring(separator + 1).trim() else error
        return PrivilegedOperationResult(
            ok = false,
            code = code.ifBlank { "uninstall_failed" },
            message = detail.ifBlank { "The app could not be uninstalled." },
        )
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
    ) = PrivilegedOperationResult(
        ok = false,
        code = "shizuku_unavailable",
        message = "Privileged app backend is not configured.",
    )
}
