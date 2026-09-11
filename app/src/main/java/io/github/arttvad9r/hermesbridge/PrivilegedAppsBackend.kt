package io.github.arttvad9r.hermesbridge

interface PrivilegedAppsBackend {
    fun readiness(): PrivilegedBackendReadiness

    suspend fun install(
        artifact: VerifiedApkArtifact,
        replace: Boolean,
    ): PrivilegedOperationResult

    suspend fun uninstall(
        packageName: String,
        keepData: Boolean,
    ): PrivilegedOperationResult

    suspend fun forceStop(packageName: String): PrivilegedOperationResult

    suspend fun revokePermission(
        packageName: String,
        permissionName: String,
        userId: Int,
    ): PrivilegedOperationResult = PrivilegedOperationResult(
        ok = false,
        code = "permission_revoke_unavailable",
        message = "Permission revocation is not configured in this backend.",
    )
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
 * Package mutations are executed in a typed Shizuku UserService. The Binder
 * contract has one method per supported operation and does not expose shell,
 * argv, or a generic command passthrough to the app process or Hermes agent.
 * See THIRD_PARTY_NOTICES.md for attribution.
 */
class DroidMcpShizukuAppsBackend : PrivilegedAppsBackend {
    override fun readiness(): PrivilegedBackendReadiness =
        ShizukuPrivilegedUserServiceClient.readiness()

    override suspend fun install(
        artifact: VerifiedApkArtifact,
        replace: Boolean,
    ): PrivilegedOperationResult {
        readinessFailure()?.let { return it }
        if (!artifact.file.isFile || artifact.sizeBytes !in 1..MAX_APK_ARTIFACT_BYTES) {
            return PrivilegedOperationResult(
                ok = false,
                code = "invalid_apk_artifact",
                message = "Verified APK file is missing or has an invalid size.",
            )
        }
        if (artifact.file.length() != artifact.sizeBytes) {
            return PrivilegedOperationResult(
                ok = false,
                code = "invalid_apk_artifact",
                message = "Verified APK size changed before installation.",
            )
        }
        return ShizukuPrivilegedUserServiceClient.install(artifact, replace)
    }

    override suspend fun uninstall(
        packageName: String,
        keepData: Boolean,
    ): PrivilegedOperationResult {
        readinessFailure()?.let { return it }
        invalidPackageFailure(packageName)?.let { return it }
        return ShizukuPrivilegedUserServiceClient.uninstall(packageName, keepData)
    }

    override suspend fun forceStop(packageName: String): PrivilegedOperationResult {
        readinessFailure()?.let { return it }
        invalidPackageFailure(packageName)?.let { return it }
        return ShizukuPrivilegedUserServiceClient.forceStop(packageName)
    }

    override suspend fun revokePermission(
        packageName: String,
        permissionName: String,
        userId: Int,
    ): PrivilegedOperationResult {
        readinessFailure()?.let { return it }
        invalidPackageFailure(packageName)?.let { return it }
        invalidPermissionFailure(permissionName)?.let { return it }
        if (!isSupportedAndroidUserId(userId)) {
            return PrivilegedOperationResult(
                ok = false,
                code = "invalid_user_id",
                message = "Android user ID is outside the supported range.",
            )
        }
        return ShizukuPrivilegedUserServiceClient.revokePermission(
            packageName = packageName,
            permissionName = permissionName,
            userId = userId,
        )
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
        if (isValidAndroidQualifiedName(packageName)) {
            null
        } else {
            PrivilegedOperationResult(
                ok = false,
                code = "invalid_package_name",
                message = "The package name is invalid.",
            )
        }

    private fun invalidPermissionFailure(permissionName: String): PrivilegedOperationResult? =
        if (isValidAndroidQualifiedName(permissionName)) {
            null
        } else {
            PrivilegedOperationResult(
                ok = false,
                code = "invalid_permission_name",
                message = "The permission name is invalid.",
            )
        }
}

internal fun buildPmInstallCommand(stagedApkPath: String, replace: Boolean): Array<String> {
    require(isShizukuApkStagingPath(stagedApkPath)) { "Invalid staged APK path." }
    return buildList {
        add("pm")
        add("install")
        if (replace) add("-r")
        add(stagedApkPath)
    }.toTypedArray()
}

internal fun buildPmUninstallCommand(
    packageName: String,
    keepData: Boolean,
): Array<String> {
    require(isValidAndroidQualifiedName(packageName)) { "Invalid package name." }
    return buildList {
        add("pm")
        add("uninstall")
        if (keepData) add("-k")
        add(packageName)
    }.toTypedArray()
}

internal fun buildAmForceStopCommand(packageName: String): Array<String> {
    require(isValidAndroidQualifiedName(packageName)) { "Invalid package name." }
    return arrayOf("am", "force-stop", packageName)
}

internal fun buildPmRevokePermissionCommand(
    packageName: String,
    permissionName: String,
    userId: Int,
): Array<String> {
    require(isValidAndroidQualifiedName(packageName)) { "Invalid package name." }
    require(isValidAndroidQualifiedName(permissionName)) { "Invalid permission name." }
    require(isSupportedAndroidUserId(userId)) { "Invalid Android user ID." }
    return arrayOf(
        "pm",
        "revoke",
        "--user",
        userId.toString(),
        packageName,
        permissionName,
    )
}

internal fun isSupportedAndroidUserId(userId: Int): Boolean =
    userId in 0..MAX_ANDROID_USER_ID

object DisabledPrivilegedAppsBackend : PrivilegedAppsBackend {
    override fun readiness() = PrivilegedBackendReadiness(
        ready = false,
        code = "shizuku_unavailable",
        message = "Privileged app backend is not configured.",
    )

    override suspend fun install(
        artifact: VerifiedApkArtifact,
        replace: Boolean,
    ) = unavailable()

    override suspend fun uninstall(
        packageName: String,
        keepData: Boolean,
    ) = unavailable()

    override suspend fun forceStop(packageName: String) = unavailable()

    override suspend fun revokePermission(
        packageName: String,
        permissionName: String,
        userId: Int,
    ) = unavailable()

    private fun unavailable() = PrivilegedOperationResult(
        ok = false,
        code = "shizuku_unavailable",
        message = "Privileged app backend is not configured.",
    )
}

private const val MAX_ANDROID_USER_ID = 99_999
