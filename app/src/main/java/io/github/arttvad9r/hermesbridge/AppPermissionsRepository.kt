package io.github.arttvad9r.hermesbridge

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import java.util.Locale

data class AppPermissionSnapshot(
    val name: String,
    val granted: Boolean,
    val protection: String,
    val dangerous: Boolean,
    val group: String?,
    val implicit: Boolean,
    val neverForLocation: Boolean,
)

data class AppPermissionsSnapshot(
    val packageName: String,
    val permissions: List<AppPermissionSnapshot>,
)

interface AppPermissionsRepository {
    fun read(packageName: String): AppPermissionsSnapshot
}

class AppPermissionsUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

class AndroidAppPermissionsRepository(context: Context) : AppPermissionsRepository {
    private val packageManager = context.applicationContext.packageManager

    override fun read(packageName: String): AppPermissionsSnapshot {
        val packageInfo = try {
            getPackageInfo(packageName)
        } catch (error: PackageManager.NameNotFoundException) {
            throw AppPermissionsUnavailableException("Package is not visible or no longer installed.", error)
        } catch (error: SecurityException) {
            throw AppPermissionsUnavailableException("Android denied access to package permission metadata.", error)
        }

        val requested = packageInfo.requestedPermissions.orEmpty()
        val flags = packageInfo.requestedPermissionsFlags.orEmpty()
        val permissions = requested
            .asSequence()
            .take(MAX_PERMISSIONS)
            .mapIndexedNotNull { index, permissionName ->
                val name = permissionName.trim()
                    .takeIf { it.isNotEmpty() && it.length <= MAX_PERMISSION_NAME_LENGTH }
                    ?: return@mapIndexedNotNull null
                val requestedFlags = flags.getOrElse(index) { 0 }
                val permissionInfo = getPermissionInfoOrNull(name)
                val protection = protectionLabel(permissionInfo)
                AppPermissionSnapshot(
                    name = name,
                    granted = requestedFlags and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0,
                    protection = protection,
                    dangerous = protection == PROTECTION_DANGEROUS_LABEL,
                    group = permissionInfo?.group?.take(MAX_PERMISSION_GROUP_LENGTH),
                    implicit = requestedFlags and PackageInfo.REQUESTED_PERMISSION_IMPLICIT != 0,
                    neverForLocation = requestedFlags and PackageInfo.REQUESTED_PERMISSION_NEVER_FOR_LOCATION != 0,
                )
            }
            .distinctBy { it.name }
            .sortedWith(
                compareByDescending<AppPermissionSnapshot> { it.dangerous && it.granted }
                    .thenByDescending { it.dangerous }
                    .thenByDescending { it.granted }
                    .thenBy { it.name.lowercase(Locale.ROOT) }
            )
            .toList()

        return AppPermissionsSnapshot(
            packageName = packageName,
            permissions = permissions,
        )
    }

    @Suppress("DEPRECATION")
    private fun getPackageInfo(packageName: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
            )
        } else {
            packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        }

    @Suppress("DEPRECATION")
    private fun getPermissionInfoOrNull(name: String): PermissionInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPermissionInfo(name, PackageManager.PermissionInfoFlags.of(0L))
        } else {
            packageManager.getPermissionInfo(name, 0)
        }
    }.getOrNull()

    private fun protectionLabel(permissionInfo: PermissionInfo?): String {
        if (permissionInfo == null) return PROTECTION_UNKNOWN_LABEL
        return when (permissionInfo.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE) {
            PermissionInfo.PROTECTION_NORMAL -> "normal"
            PermissionInfo.PROTECTION_DANGEROUS -> PROTECTION_DANGEROUS_LABEL
            PermissionInfo.PROTECTION_SIGNATURE -> "signature"
            PermissionInfo.PROTECTION_INTERNAL -> "internal"
            else -> "other"
        }
    }

    companion object {
        const val MAX_PERMISSIONS = 300
        private const val MAX_PERMISSION_NAME_LENGTH = 255
        private const val MAX_PERMISSION_GROUP_LENGTH = 255
        private const val PROTECTION_DANGEROUS_LABEL = "dangerous"
        private const val PROTECTION_UNKNOWN_LABEL = "unknown"
    }
}
