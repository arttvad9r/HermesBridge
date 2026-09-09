package io.github.arttvad9r.hermesbridge

import android.os.UserHandle
import io.github.arttvad9r.hermesbridge.protocol.CommandRequestPayload
import io.github.arttvad9r.hermesbridge.protocol.CommandResultPayload
import io.github.arttvad9r.hermesbridge.protocol.ProtocolError
import io.github.arttvad9r.hermesbridge.security.ApprovalDecision
import io.github.arttvad9r.hermesbridge.security.BridgeTool
import io.github.arttvad9r.hermesbridge.security.DefaultToolPolicy
import io.github.arttvad9r.hermesbridge.security.ToolRisk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AppPermissionRevokeToolHandler(
    private val appsRepository: InstalledAppsRepository,
    private val permissionsRepository: AppPermissionsRepository?,
    private val privilegedBackend: PrivilegedAppsBackend,
    private val userIdProvider: () -> Int = { UserHandle.myUserId() },
) {
    suspend fun execute(request: CommandRequestPayload): CommandResultPayload {
        if (request.arguments.keys.any { it != PACKAGE_NAME && it != PERMISSION_NAME }) {
            return invalidArguments(request.requestId)
        }
        val packageName = stringArgument(request, PACKAGE_NAME)
            ?.trim()
            ?.takeIf(::isValidAndroidName)
            ?: return invalidArguments(request.requestId)
        val permissionName = stringArgument(request, PERMISSION_NAME)
            ?.trim()
            ?.takeIf(::isValidAndroidName)
            ?: return invalidArguments(request.requestId)

        if (packageName == HERMES_BRIDGE_PACKAGE) {
            return failure(
                request.requestId,
                "protected_package",
                "Hermes Bridge permissions cannot be changed through the agent.",
            )
        }

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
        } catch (error: Exception) {
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

        val permission = snapshot.permissions.firstOrNull { it.name == permissionName }
            ?: return failure(
                request.requestId,
                "permission_not_requested",
                "The app does not request this permission in the visible package metadata.",
            )
        if (!permission.dangerous) {
            return failure(
                request.requestId,
                "permission_not_dangerous",
                "Hermes Bridge only allows agent revocation of Android dangerous runtime permissions.",
            )
        }
        if (!permission.granted) {
            return CommandResultPayload(
                requestId = request.requestId,
                ok = true,
                result = buildJsonObject {
                    put(PACKAGE_NAME, packageName)
                    put(PERMISSION_NAME, permissionName)
                    put("revoked", false)
                    put("alreadyRevoked", true)
                },
            )
        }

        val userId = userIdProvider()
        if (userId !in 0..MAX_ANDROID_USER_ID) {
            return failure(
                request.requestId,
                "invalid_user_id",
                "Current Android user ID is outside the supported range.",
            )
        }
        val readiness = privilegedBackend.readiness()
        if (!readiness.ready) {
            return failure(
                request.requestId,
                readiness.code ?: "shizuku_unavailable",
                readiness.message ?: "Shizuku is not ready.",
            )
        }

        val normalizedArguments = buildJsonObject {
            put(PACKAGE_NAME, packageName)
            put(PERMISSION_NAME, permissionName)
            put(USER_ID, userId)
        }
        requireApproval(
            request = request,
            normalizedArguments = normalizedArguments,
            summary = "Отозвать $permissionName у ${visibleApp.label.take(80)} ($packageName)",
        )?.let { return it }

        val outcome = privilegedBackend.revokePermission(
            packageName = packageName,
            permissionName = permissionName,
            userId = userId,
        )
        if (!outcome.ok) {
            return failure(
                request.requestId,
                outcome.code ?: "permission_revoke_failed",
                outcome.message ?: "Android did not revoke the permission.",
            )
        }

        return CommandResultPayload(
            requestId = request.requestId,
            ok = true,
            result = buildJsonObject {
                put(PACKAGE_NAME, packageName)
                put(PERMISSION_NAME, permissionName)
                put("revoked", true)
                put("alreadyRevoked", false)
            },
        )
    }

    private fun requireApproval(
        request: CommandRequestPayload,
        normalizedArguments: JsonObject,
        summary: String,
    ): CommandResultPayload? {
        if (
            DefaultToolPolicy.decision(BridgeTool(TOOL_NAME, ToolRisk.PRIVILEGED)) !=
            ApprovalDecision.REQUIRE_APPROVAL
        ) {
            return failure(
                request.requestId,
                "policy_denied",
                "Local policy does not permit permission revocation.",
            )
        }
        if (BridgeApprovalRuntime.consumeApproved(TOOL_NAME, normalizedArguments) != null) return null

        val ticket = BridgeApprovalRuntime.request(
            tool = TOOL_NAME,
            risk = ToolRisk.PRIVILEGED,
            arguments = normalizedArguments,
            displaySummary = summary,
        )
        return failure(
            request.requestId,
            "approval_required",
            "User approval is required (${ticket.id}). Retry the same command after approval.",
        )
    }

    private fun stringArgument(request: CommandRequestPayload, name: String): String? {
        val primitive = request.arguments[name] as? JsonPrimitive ?: return null
        return primitive.takeIf { it.isString }?.content
    }

    private fun isValidAndroidName(value: String): Boolean =
        value.length in 3..MAX_ANDROID_NAME_LENGTH && ANDROID_NAME_REGEX.matches(value)

    private fun invalidArguments(requestId: String) = failure(
        requestId,
        "invalid_arguments",
        "apps.revokePermission requires exactly packageName and permissionName.",
    )

    private fun failure(requestId: String, code: String, message: String) =
        CommandResultPayload(
            requestId = requestId,
            ok = false,
            error = ProtocolError(code, message),
        )

    companion object {
        const val TOOL_NAME = "apps.revokePermission"
        private const val PACKAGE_NAME = "packageName"
        private const val PERMISSION_NAME = "permissionName"
        private const val USER_ID = "userId"
        private const val HERMES_BRIDGE_PACKAGE = "io.github.arttvad9r.hermesbridge"
        private const val MAX_ANDROID_NAME_LENGTH = 255
        private const val MAX_ANDROID_USER_ID = 99_999
        private val ANDROID_NAME_REGEX = Regex(
            "^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$"
        )
    }
}
