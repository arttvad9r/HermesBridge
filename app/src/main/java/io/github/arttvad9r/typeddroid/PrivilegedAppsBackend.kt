package io.github.arttvad9r.typeddroid

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

internal fun buildPmInstallCommand(stagedApkPath: String, replace: Boolean): Array<String> {
    require(isPrivilegedApkStagingPath(stagedApkPath)) { "Invalid staged APK path." }
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
        code = "embedded_privileged_unavailable",
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
        code = "embedded_privileged_unavailable",
        message = "Privileged app backend is not configured.",
    )
}

private const val MAX_ANDROID_USER_ID = 99_999
