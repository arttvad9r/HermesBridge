package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.protocol.CommandRequestPayload
import io.github.arttvad9r.hermesbridge.protocol.CommandResultPayload

/**
 * Top-level typed command router for the Android bridge.
 *
 * Specialized handlers live here only when they are intentionally kept outside the legacy core
 * registry. Unknown commands still fall through to BridgeToolRegistry, which is default-deny.
 */
class BridgeCommandRouter(
    private val coreRegistry: BridgeToolRegistry,
    appsRepository: InstalledAppsRepository,
    appPermissionsRepository: AppPermissionsRepository?,
    privilegedAppsBackend: PrivilegedAppsBackend = DisabledPrivilegedAppsBackend,
) {
    private val replayGuard = CommandReplayGuard()
    private val appsListHandler = AppsListToolHandler(appsRepository)
    private val appPermissionsHandler = AppPermissionsToolHandler(
        appsRepository = appsRepository,
        permissionsRepository = appPermissionsRepository,
    )
    private val appPermissionsAuditHandler = AppPermissionsAuditToolHandler(
        appsRepository = appsRepository,
        permissionsRepository = appPermissionsRepository,
    )
    private val appPermissionRevokeHandler = AppPermissionRevokeToolHandler(
        appsRepository = appsRepository,
        permissionsRepository = appPermissionsRepository,
        privilegedBackend = privilegedAppsBackend,
    )

    suspend fun execute(request: CommandRequestPayload): CommandResultPayload {
        val outcome = replayGuard.execute(request) {
            remoteSafeCommandResult(
                when (request.tool) {
                    AppsListToolHandler.TOOL_NAME -> appsListHandler.execute(request)
                    AppPermissionsToolHandler.TOOL_NAME -> appPermissionsHandler.execute(request)
                    AppPermissionsAuditToolHandler.TOOL_NAME -> appPermissionsAuditHandler.execute(request)
                    AppPermissionRevokeToolHandler.TOOL_NAME -> appPermissionRevokeHandler.execute(request)
                    else -> coreRegistry.execute(request)
                }
            )
        }
        // Replay-guard generated failures do not pass through the action above, so enforce the
        // boundary once more on the final result. The sanitizer is intentionally idempotent.
        val result = remoteSafeCommandResult(outcome.result)
        if (outcome.shouldAudit) {
            BridgeAuditRuntime.recordCommand(request.tool, result)
        }
        return result
    }
}
