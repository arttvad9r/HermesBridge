package io.github.arttvad9r.typeddroid

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class EmbeddedPrivilegedAppsBackend(context: Context) : PrivilegedAppsBackend {
    private val store = EmbeddedPrivilegedStore(context)

    override fun readiness(): PrivilegedBackendReadiness = embeddedHelperReadiness(store)

    override suspend fun install(
        artifact: VerifiedApkArtifact,
        replace: Boolean,
    ): PrivilegedOperationResult = runOperation("install_failed") { client ->
        if (!artifact.file.isFile || artifact.sizeBytes !in 1..MAX_APK_ARTIFACT_BYTES ||
            artifact.file.length() != artifact.sizeBytes) {
            return@runOperation PrivilegedOperationResult(
                ok = false,
                code = "invalid_apk_artifact",
                message = "Verified APK file is missing or changed before installation.",
            )
        }
        client.installApk(artifact, replace).toPrivilegedResult(
            failureCode = "install_failed",
            failureMessage = "Android package manager rejected the APK.",
        )
    }

    override suspend fun uninstall(
        packageName: String,
        keepData: Boolean,
    ): PrivilegedOperationResult = runOperation("uninstall_failed") { client ->
        if (!isValidAndroidQualifiedName(packageName)) {
            return@runOperation invalidName("invalid_package_name", "The package name is invalid.")
        }
        client.uninstall(packageName, keepData).toPrivilegedResult(
            failureCode = "uninstall_failed",
            failureMessage = "Android package manager could not uninstall the package.",
        )
    }

    override suspend fun forceStop(packageName: String): PrivilegedOperationResult =
        runOperation("force_stop_failed") { client ->
            if (!isValidAndroidQualifiedName(packageName)) {
                return@runOperation invalidName("invalid_package_name", "The package name is invalid.")
            }
            client.forceStop(packageName).toPrivilegedResult(
                failureCode = "force_stop_failed",
                failureMessage = "Android could not force-stop the package.",
            )
        }

    override suspend fun revokePermission(
        packageName: String,
        permissionName: String,
        userId: Int,
    ): PrivilegedOperationResult = runOperation("permission_revoke_failed") { client ->
        if (!isValidAndroidQualifiedName(packageName)) {
            return@runOperation invalidName("invalid_package_name", "The package name is invalid.")
        }
        if (!isValidAndroidQualifiedName(permissionName)) {
            return@runOperation invalidName("invalid_permission_name", "The permission name is invalid.")
        }
        if (!isSupportedAndroidUserId(userId)) {
            return@runOperation invalidName("invalid_user_id", "Android user ID is outside the supported range.")
        }
        client.revokePermission(packageName, permissionName, userId).toPrivilegedResult(
            failureCode = "permission_revoke_failed",
            failureMessage = "Android could not revoke the permission.",
        )
    }

    private suspend fun runOperation(
        failureCode: String,
        block: (EmbeddedPrivilegedClient) -> PrivilegedOperationResult,
    ): PrivilegedOperationResult = withContext(Dispatchers.IO) {
        val endpoint = store.endpoint()
            ?: return@withContext unavailableResult()
        try {
            block(EmbeddedPrivilegedClient(endpoint))
        } catch (error: CancellationException) {
            throw error
        } catch (error: EmbeddedPrivilegedRemoteException) {
            PrivilegedOperationResult(
                ok = false,
                code = if (error.timedOut) "${failureCode}_timeout" else failureCode,
                message = (error.message ?: "The embedded privileged operation failed.").take(300),
            )
        } catch (_: Throwable) {
            runCatching(store::clear)
            PrivilegedOperationResult(
                ok = false,
                code = failureCode,
                message = "The embedded privileged helper is unavailable or the operation failed.",
            )
        }
    }

    private fun invalidName(code: String, message: String) =
        PrivilegedOperationResult(ok = false, code = code, message = message)

    private fun unavailableResult() = PrivilegedOperationResult(
        ok = false,
        code = "embedded_privileged_unavailable",
        message = "Restore full device access in TypedDroid before using privileged app operations.",
    )
}

internal fun embeddedHelperReadiness(store: EmbeddedPrivilegedStore): PrivilegedBackendReadiness {
    val endpoint = store.endpoint()
        ?: return PrivilegedBackendReadiness(
            ready = false,
            code = "embedded_privileged_unavailable",
            message = "Embedded privileged helper has not been bootstrapped.",
        )
    val ready = EmbeddedPrivilegedClient(endpoint).ping()
    if (!ready) runCatching(store::clear)
    return if (ready) {
        PrivilegedBackendReadiness(ready = true)
    } else {
        PrivilegedBackendReadiness(
            ready = false,
            code = "embedded_privileged_unavailable",
            message = "Embedded privileged helper is no longer running.",
        )
    }
}

private fun EmbeddedPrivilegedExecResult.toPrivilegedResult(
    failureCode: String,
    failureMessage: String,
): PrivilegedOperationResult = if (exitCode == 0 && !stdoutTruncated && !stderrTruncated) {
    PrivilegedOperationResult(ok = true)
} else {
    PrivilegedOperationResult(ok = false, code = failureCode, message = failureMessage)
}
