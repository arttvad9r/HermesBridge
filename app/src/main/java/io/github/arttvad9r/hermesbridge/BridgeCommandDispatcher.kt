package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.protocol.CommandRequestPayload
import io.github.arttvad9r.hermesbridge.protocol.CommandResultPayload
import io.github.arttvad9r.hermesbridge.protocol.ProtocolError
import io.github.arttvad9r.hermesbridge.security.ApprovalDecision
import io.github.arttvad9r.hermesbridge.security.BridgeTool
import io.github.arttvad9r.hermesbridge.security.DefaultToolPolicy
import io.github.arttvad9r.hermesbridge.security.ToolRisk
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class BridgeCommandDispatcher(
    private val coreRegistry: BridgeToolRegistry,
    private val batteryDiagnosticsBackend: BatteryDiagnosticsBackend = DisabledBatteryDiagnosticsBackend,
) {
    suspend fun execute(request: CommandRequestPayload): CommandResultPayload =
        if (request.tool == BATTERY_USAGE) executeBatteryUsage(request) else coreRegistry.execute(request)

    private suspend fun executeBatteryUsage(request: CommandRequestPayload): CommandResultPayload {
        if (request.arguments.isNotEmpty()) {
            return failure(
                request.requestId,
                "invalid_arguments",
                "battery.usage does not accept arguments.",
            )
        }
        if (
            DefaultToolPolicy.decision(BridgeTool(BATTERY_USAGE, ToolRisk.READ_ONLY)) !=
            ApprovalDecision.ALLOW
        ) {
            return failure(request.requestId, "policy_denied", "Local policy did not allow battery diagnostics.")
        }

        val readiness = batteryDiagnosticsBackend.readiness()
        if (!readiness.ready) {
            return failure(
                request.requestId,
                readiness.code ?: "shizuku_unavailable",
                readiness.message ?: "Shizuku is not ready for battery diagnostics.",
            )
        }

        val outcome = try {
            batteryDiagnosticsBackend.readUsage()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return failure(
                request.requestId,
                "battery_stats_failed",
                (error.message ?: "Battery diagnostics failed.").take(200),
            )
        }
        if (!outcome.ok) {
            return failure(
                request.requestId,
                outcome.code ?: "battery_stats_failed",
                outcome.message ?: "Battery diagnostics failed.",
            )
        }
        val snapshot = outcome.snapshot
            ?: return failure(request.requestId, "battery_stats_failed", "Battery diagnostics returned no snapshot.")

        return CommandResultPayload(
            requestId = request.requestId,
            ok = true,
            result = buildJsonObject {
                if (snapshot.checkinVersion == null) put("checkinVersion", JsonNull)
                else put("checkinVersion", snapshot.checkinVersion)

                val summary = snapshot.powerSummary
                if (summary == null) {
                    put("powerSummary", JsonNull)
                } else {
                    put(
                        "powerSummary",
                        buildJsonObject {
                            put("batteryCapacityMah", summary.batteryCapacityMah)
                            put("computedPowerMah", summary.computedPowerMah)
                            put("minDrainedPowerMah", summary.minDrainedPowerMah)
                            put("maxDrainedPowerMah", summary.maxDrainedPowerMah)
                        },
                    )
                }

                put(
                    "systemPowerItems",
                    buildJsonArray {
                        snapshot.systemPowerItems.forEach { item ->
                            add(
                                buildJsonObject {
                                    put("label", item.label)
                                    put("mah", item.mah)
                                }
                            )
                        }
                    },
                )
                put(
                    "topUids",
                    buildJsonArray {
                        snapshot.topUids.forEach { item ->
                            add(
                                buildJsonObject {
                                    put("uid", item.uid)
                                    put("mah", item.mah)
                                    put(
                                        "packageNames",
                                        buildJsonArray { item.packageNames.forEach { add(it) } },
                                    )
                                }
                            )
                        }
                    },
                )
                put(
                    "topPartialWakeLocks",
                    buildJsonArray {
                        snapshot.topPartialWakeLocks.forEach { item ->
                            add(
                                buildJsonObject {
                                    put("uid", item.uid)
                                    put("name", item.name)
                                    put("partialTimeMillis", item.partialTimeMillis)
                                    put("partialCount", item.partialCount)
                                    if (item.backgroundPartialTimeMillis == null) {
                                        put("backgroundPartialTimeMillis", JsonNull)
                                    } else {
                                        put("backgroundPartialTimeMillis", item.backgroundPartialTimeMillis)
                                    }
                                    if (item.backgroundPartialCount == null) {
                                        put("backgroundPartialCount", JsonNull)
                                    } else {
                                        put("backgroundPartialCount", item.backgroundPartialCount)
                                    }
                                    put(
                                        "packageNames",
                                        buildJsonArray { item.packageNames.forEach { add(it) } },
                                    )
                                }
                            )
                        }
                    },
                )
            },
        )
    }

    private fun failure(requestId: String, code: String, message: String) =
        CommandResultPayload(
            requestId = requestId,
            ok = false,
            error = ProtocolError(code, message),
        )

    companion object {
        const val BATTERY_USAGE = "battery.usage"
    }
}
