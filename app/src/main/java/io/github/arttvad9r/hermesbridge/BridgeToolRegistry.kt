package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.protocol.CommandRequestPayload
import io.github.arttvad9r.hermesbridge.protocol.CommandResultPayload
import io.github.arttvad9r.hermesbridge.protocol.ProtocolError
import io.github.arttvad9r.hermesbridge.security.ApprovalDecision
import io.github.arttvad9r.hermesbridge.security.BridgeTool
import io.github.arttvad9r.hermesbridge.security.DefaultToolPolicy
import io.github.arttvad9r.hermesbridge.security.ToolRisk
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class BridgeToolRegistry(
    private val healthRepository: DeviceHealthRepository,
) {
    suspend fun execute(request: CommandRequestPayload): CommandResultPayload {
        return when (request.tool) {
            DEVICE_HEALTH -> executeDeviceHealth(request)
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

        val decision = DefaultToolPolicy.decision(
            BridgeTool(DEVICE_HEALTH, ToolRisk.READ_ONLY)
        )
        if (decision != ApprovalDecision.ALLOW) {
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

    private fun failure(requestId: String, code: String, message: String) =
        CommandResultPayload(
            requestId = requestId,
            ok = false,
            error = ProtocolError(code, message),
        )

    companion object {
        const val DEVICE_HEALTH = "device.health"
    }
}
