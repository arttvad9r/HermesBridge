package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.protocol.CommandRequestPayload
import io.github.arttvad9r.hermesbridge.protocol.CommandResultPayload
import io.github.arttvad9r.hermesbridge.protocol.ProtocolError
import io.github.arttvad9r.hermesbridge.security.ApprovalDecision
import io.github.arttvad9r.hermesbridge.security.BridgeTool
import io.github.arttvad9r.hermesbridge.security.DefaultToolPolicy
import io.github.arttvad9r.hermesbridge.security.ToolRisk
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class RemotePermissionsProjection(
    val permissions: List<AppPermissionSnapshot>,
    val totalPermissionCount: Int,
    val truncated: Boolean,
)

internal fun projectPermissionsForRemoteResult(
    permissions: List<AppPermissionSnapshot>,
): RemotePermissionsProjection {
    val projected = permissions.asSequence()
        .filter { permission -> isValidAndroidQualifiedName(permission.name) }
        .take(MAX_REMOTE_PERMISSION_ENTRIES)
        .map { permission ->
            permission.copy(
                protection = boundedRemoteText(
                    permission.protection,
                    MAX_REMOTE_PERMISSION_PROTECTION_CHARS,
                ),
                group = permission.group?.let {
                    boundedRemoteText(it, MAX_REMOTE_PERMISSION_GROUP_CHARS)
                },
            )
        }
        .toList()
    return RemotePermissionsProjection(
        permissions = projected,
        totalPermissionCount = permissions.size,
        truncated = projected.size < permissions.size,
    )
}

class AppPermissionsToolHandler(
    private val appsRepository: InstalledAppsRepository,
    private val permissionsRepository: AppPermissionsRepository?,
) {
    fun execute(request: CommandRequestPayload): CommandResultPayload {
        if (
            DefaultToolPolicy.decision(BridgeTool(TOOL_NAME, ToolRisk.READ_ONLY)) !=
            ApprovalDecision.ALLOW
        ) {
            return failure(request.requestId, "policy_denied", "Local policy did not allow the tool.")
        }
        if (request.arguments.keys.any { it != PACKAGE_NAME }) {
            return invalidArguments(request.requestId)
        }

        val packageName = (request.arguments[PACKAGE_NAME] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.trim()
            ?.takeIf(::isValidAndroidQualifiedName)
            ?: return invalidArguments(request.requestId)

        val visibleApp = appsRepository.listLaunchableApps()
            .firstOrNull { it.packageName == packageName }
            ?: return failure(
                request.requestId,
                "app_not_visible",
                "The requested package is not launcher-visible to Hermes Bridge.",
            )

        val repository = permissionsRepository
            ?: return failure(
                request.requestId,
                "app_permissions_unavailable",
                "App permission metadata repository is not configured.",
            )

        val snapshot = try {
            repository.read(packageName)
        } catch (error: AppPermissionsUnavailableException) {
            return failure(
                request.requestId,
                "app_permissions_unavailable",
                (error.message ?: "App permission metadata is unavailable.").take(200),
            )
        } catch (error: Throwable) {
            return failure(
                request.requestId,
                "app_permissions_failed",
                (error.message ?: "App permission metadata query failed.").take(200),
            )
        }
        if (snapshot.packageName != packageName) {
            return failure(
                request.requestId,
                "app_permissions_failed",
                "Permission metadata returned an inconsistent package identity.",
            )
        }

        val dangerousCount = snapshot.permissions.count { it.dangerous }
        val dangerousGrantedCount = snapshot.permissions.count { it.dangerous && it.granted }
        val grantedCount = snapshot.permissions.count { it.granted }
        val projection = projectPermissionsForRemoteResult(snapshot.permissions)

        return CommandResultPayload(
            requestId = request.requestId,
            ok = true,
            result = buildJsonObject {
                put("packageName", packageName)
                put("label", boundedRemoteText(visibleApp.label, MAX_REMOTE_APP_LABEL_CHARS))
                if (visibleApp.versionName == null) put("versionName", JsonNull)
                else put(
                    "versionName",
                    boundedRemoteText(visibleApp.versionName, MAX_REMOTE_APP_VERSION_NAME_CHARS),
                )
                put("versionCode", visibleApp.versionCode)
                put("systemApp", visibleApp.systemApp)
                put("enabled", visibleApp.enabled)
                put("permissionCount", projection.totalPermissionCount)
                put("returnedPermissionCount", projection.permissions.size)
                put("permissionsTruncated", projection.truncated)
                put("grantedCount", grantedCount)
                put("dangerousCount", dangerousCount)
                put("dangerousGrantedCount", dangerousGrantedCount)
                put(
                    "permissions",
                    buildJsonArray {
                        projection.permissions.forEach { permission ->
                            add(
                                buildJsonObject {
                                    put("name", permission.name)
                                    put("granted", permission.granted)
                                    put("protection", permission.protection)
                                    put("dangerous", permission.dangerous)
                                    if (permission.group == null) put("group", JsonNull)
                                    else put("group", permission.group)
                                    put("implicit", permission.implicit)
                                    put("neverForLocation", permission.neverForLocation)
                                }
                            )
                        }
                    },
                )
            },
        )
    }

    private fun invalidArguments(requestId: String) = failure(
        requestId,
        "invalid_arguments",
        "apps.permissions requires exactly one valid packageName.",
    )

    private fun failure(requestId: String, code: String, message: String) =
        CommandResultPayload(
            requestId = requestId,
            ok = false,
            error = ProtocolError(code, message),
        )

    companion object {
        const val TOOL_NAME = "apps.permissions"
        private const val PACKAGE_NAME = "packageName"
    }
}

internal const val MAX_ANDROID_QUALIFIED_NAME_CHARS = 255
internal const val MAX_REMOTE_PERMISSION_ENTRIES = 96
internal const val MAX_REMOTE_PERMISSION_GROUP_CHARS = 120
internal const val MAX_REMOTE_PERMISSION_PROTECTION_CHARS = 32

private val ANDROID_QUALIFIED_NAME_REGEX = Regex(
    "^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$"
)

internal fun isValidAndroidQualifiedName(value: String): Boolean =
    value.length in 3..MAX_ANDROID_QUALIFIED_NAME_CHARS && ANDROID_QUALIFIED_NAME_REGEX.matches(value)
