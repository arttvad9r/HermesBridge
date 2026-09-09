package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.protocol.CommandRequestPayload
import io.github.arttvad9r.hermesbridge.protocol.CommandResultPayload
import io.github.arttvad9r.hermesbridge.protocol.ProtocolError
import io.github.arttvad9r.hermesbridge.security.ApprovalDecision
import io.github.arttvad9r.hermesbridge.security.BridgeTool
import io.github.arttvad9r.hermesbridge.security.DefaultToolPolicy
import io.github.arttvad9r.hermesbridge.security.ToolRisk
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class BridgeToolRegistry(
    private val healthRepository: DeviceHealthRepository,
    private val appsRepository: InstalledAppsRepository,
) {
    suspend fun execute(request: CommandRequestPayload): CommandResultPayload {
        return when (request.tool) {
            DEVICE_HEALTH -> executeDeviceHealth(request)
            APPS_LIST -> executeAppsList(request)
            else -> failure(
                request.requestId,
                "unknown_tool",
                "Tool is not present in the Hermes Bridge allowlist.",
            )
        }
    }

    private fun executeDeviceHealth(request: CommandRequestPayload): CommandResultPayload {
        if (request.arguments.isNotEmpty()) {
            return failure(
                request.requestId,
                "invalid_arguments",
                "device.health does not accept arguments.",
            )
        }

        if (!isAllowed(DEVICE_HEALTH)) {
            return failure(
                request.requestId,
                "policy_denied",
                "Local policy did not allow the tool.",
            )
        }

        val health = healthRepository.snapshot()
        val result = buildJsonObject {
            if (health.batteryPercent == null) {
                put("batteryPercent", JsonNull)
            } else {
                put("batteryPercent", health.batteryPercent)
            }
            put("availableMemoryBytes", health.availableMemoryBytes)
            put("totalMemoryBytes", health.totalMemoryBytes)
            put("availableStorageBytes", health.availableStorageBytes)
            put("totalStorageBytes", health.totalStorageBytes)
        }
        return CommandResultPayload(
            requestId = request.requestId,
            ok = true,
            result = result,
        )
    }

    private fun executeAppsList(request: CommandRequestPayload): CommandResultPayload {
        if (request.arguments.isNotEmpty()) {
            return failure(
                request.requestId,
                "invalid_arguments",
                "apps.list does not accept arguments.",
            )
        }

        if (!isAllowed(APPS_LIST)) {
            return failure(
                request.requestId,
                "policy_denied",
                "Local policy did not allow the tool.",
            )
        }

        val apps = appsRepository.listLaunchableApps()
        val result = buildJsonObject {
            put("count", apps.size)
            put(
                "apps",
                buildJsonArray {
                    apps.forEach { app ->
                        add(
                            buildJsonObject {
                                put("packageName", app.packageName)
                                put("label", app.label)
                                if (app.versionName == null) {
                                    put("versionName", JsonNull)
                                } else {
                                    put("versionName", app.versionName)
                                }
                                put("versionCode", app.versionCode)
                                put("systemApp", app.systemApp)
                                put("enabled", app.enabled)
                            }
                        )
                    }
                },
            )
        }
        return CommandResultPayload(
            requestId = request.requestId,
            ok = true,
            result = result,
        )
    }

    private fun isAllowed(toolName: String): Boolean =
        DefaultToolPolicy.decision(
            BridgeTool(toolName, ToolRisk.READ_ONLY)
        ) == ApprovalDecision.ALLOW

    private fun failure(requestId: String, code: String, message: String) =
        CommandResultPayload(
            requestId = requestId,
            ok = false,
            error = ProtocolError(code, message),
        )

    companion object {
        const val DEVICE_HEALTH = "device.health"
        const val APPS_LIST = "apps.list"
    }
}
