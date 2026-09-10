package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.protocol.CommandResultPayload
import io.github.arttvad9r.hermesbridge.protocol.ProtocolError

/**
 * Removes arbitrary local exception/backend text before a command result crosses the Android→relay
 * boundary. Structured error codes remain useful to Hermes, while messages are selected only from
 * this local deterministic table. Envelopes are canonicalized so malformed success/failure objects
 * cannot leak fields that do not belong to their declared outcome.
 */
internal fun remoteSafeCommandResult(result: CommandResultPayload): CommandResultPayload {
    if (result.ok) {
        return if (result.error == null) result else result.copy(error = null)
    }

    val code = result.error
        ?.code
        ?.takeIf(REMOTE_ERROR_CODE_REGEX::matches)
        ?: GENERIC_ERROR_CODE
    return result.copy(
        result = null,
        error = ProtocolError(
            code = code,
            message = remoteSafeCommandErrorMessage(code),
        ),
    )
}

internal fun remoteSafeCommandErrorMessage(code: String): String = when (code) {
    "approval_required" ->
        "User approval is required on the Android device before this command can continue."
    "invalid_request_id" -> "The command request ID is invalid."
    "request_id_conflict" -> "The command request ID is already bound to another payload."
    "request_replay_busy" -> "The Android request replay guard is temporarily full."
    "invalid_arguments" -> "The command arguments are invalid."
    "unknown_tool" -> "The requested tool is not available on the Android device."
    "policy_denied" -> "Local Android policy denied the command."
    "shizuku_unavailable" -> "Shizuku is not ready for this operation."
    "usage_access_not_granted" -> "Usage Access is not granted on the Android device."
    "usage_access_unavailable" -> "Usage Access is unavailable in this build."
    "usage_query_failed" -> "Android could not query app usage."
    "file_access_not_configured" -> "A shared folder is not configured on the Android device."
    "file_access_revoked" -> "The Android file-access grant is no longer available."
    "path_not_found" -> "The requested target was not found in the granted folder."
    "not_directory" -> "A requested path component is not a directory."
    "delete_failed",
    "file_operation_failed",
    "file_operation_unavailable",
    -> "The Android file operation failed."
    "app_not_visible" -> "The requested app is not launcher-visible to Hermes Bridge."
    "app_permissions_unavailable" -> "App permission metadata is unavailable."
    "app_permissions_failed" -> "Android could not read app permission metadata."
    "permission_not_requested" -> "The app does not request the specified permission."
    "permission_not_dangerous" -> "The specified permission is not an Android dangerous runtime permission."
    "permission_revoke_failed",
    "permission_revoke_timeout",
    "permission_revoke_unavailable",
    -> "Android could not revoke the permission."
    "protected_package" -> "The requested operation is blocked for the Hermes Bridge package."
    "invalid_user_id" -> "The Android user ID is invalid."
    "apk_download_failed" -> "The staged APK could not be downloaded or verified."
    "invalid_apk",
    "invalid_apk_artifact",
    -> "The staged APK is invalid."
    "apk_install_unavailable" -> "APK installation is unavailable in this build."
    "install_failed",
    "install_timeout",
    -> "Android could not install the APK."
    "uninstall_failed",
    "uninstall_timeout",
    -> "Android could not uninstall the app."
    "force_stop_failed",
    "force_stop_timeout",
    -> "Android could not force-stop the app."
    "battery_stats_too_large" -> "Battery diagnostics exceeded the local safety limit."
    "battery_stats_failed",
    "battery_stats_timeout",
    "battery_stats_stream_failed",
    "battery_stats_parse_failed",
    -> "Battery diagnostics failed on the Android device."
    else -> GENERIC_ERROR_MESSAGE
}

private const val GENERIC_ERROR_CODE = "command_failed"
private const val GENERIC_ERROR_MESSAGE =
    "The Android command failed. Use the structured error code to identify the failure category."
private val REMOTE_ERROR_CODE_REGEX = Regex("^[a-z][a-z0-9_]{0,63}$")
