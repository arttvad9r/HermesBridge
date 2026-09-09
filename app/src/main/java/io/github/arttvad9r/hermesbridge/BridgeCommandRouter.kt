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
) {
    private val appPermissionsHandler = AppPermissionsToolHandler(
        appsRepository = appsRepository,
        permissionsRepository = appPermissionsRepository,
    )

    suspend fun execute(request: CommandRequestPayload): CommandResultPayload =
        when (request.tool) {
            AppPermissionsToolHandler.TOOL_NAME -> appPermissionsHandler.execute(request)
            else -> coreRegistry.execute(request)
        }
}
