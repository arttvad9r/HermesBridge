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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

class AppUsageToolHandler(
    private val appsRepository: InstalledAppsRepository,
    private val usageRepository: AppUsageRepository?,
) {
    fun execute(request: CommandRequestPayload): CommandResultPayload {
        if (
            DefaultToolPolicy.decision(BridgeTool(TOOL_NAME, ToolRisk.READ_ONLY)) !=
            ApprovalDecision.ALLOW
        ) {
            return failure(request.requestId, "policy_denied", "Local policy did not allow the tool.")
        }
        if (request.arguments.keys.any { it != DAYS }) {
            return invalidArguments(request.requestId)
        }

        val days = when (val value = request.arguments[DAYS]) {
            null -> DEFAULT_DAYS
            is JsonPrimitive -> value.intOrNull
                ?.takeIf { it in AndroidAppUsageRepository.MIN_DAYS..AndroidAppUsageRepository.MAX_DAYS }
                ?: return invalidArguments(request.requestId)
            else -> return invalidArguments(request.requestId)
        }

        val repository = usageRepository
            ?: return failure(
                request.requestId,
                "usage_access_unavailable",
                "Usage Access repository is not configured.",
            )
        if (!repository.hasAccess()) {
            return failure(
                request.requestId,
                "usage_access_not_granted",
                "Grant Usage Access to Hermes Bridge in Android settings before using apps.usage.",
            )
        }

        val window = try {
            repository.query(days)
        } catch (_: UsageAccessNotGrantedException) {
            return failure(
                request.requestId,
                "usage_access_not_granted",
                "Grant Usage Access to Hermes Bridge in Android settings before using apps.usage.",
            )
        } catch (error: Throwable) {
            return failure(
                request.requestId,
                "usage_query_failed",
                (error.message ?: "Android UsageStats query failed.").take(200),
            )
        }

        val observedByPackage = window.entries.associateBy { it.packageName }
        val sortedApps = appsRepository.listLaunchableApps()
            .map { app -> app to observedByPackage[app.packageName] }
            .sortedWith(
                compareByDescending<Pair<InstalledAppSnapshot, AppUsageSnapshot?>> {
                    it.second?.lastTimeUsedEpochMillis ?: Long.MIN_VALUE
                }
                    .thenByDescending { it.second?.totalTimeForegroundMillis ?: 0L }
                    .thenBy { it.first.label.lowercase() }
                    .thenBy { it.first.packageName },
            )
        val projection = projectAppsForRemoteResult(sortedApps.map { it.first })
        val resultApps = projection.apps.map { app -> app to observedByPackage[app.packageName] }

        return CommandResultPayload(
            requestId = request.requestId,
            ok = true,
            result = buildJsonObject {
                put("days", window.days)
                put("beginEpochMillis", window.beginEpochMillis)
                put("endEpochMillis", window.endEpochMillis)
                put("count", resultApps.size)
                put("totalVisibleCount", projection.totalVisibleCount)
                put("truncated", projection.truncated)
                put(
                    "apps",
                    buildJsonArray {
                        resultApps.forEach { (app, usage) ->
                            add(
                                buildJsonObject {
                                    put("packageName", app.packageName)
                                    put("label", app.label)
                                    put("systemApp", app.systemApp)
                                    put("enabled", app.enabled)
                                    put("usageObserved", usage != null)
                                    if (usage?.lastTimeUsedEpochMillis == null) {
                                        put("lastTimeUsedEpochMillis", JsonNull)
                                    } else {
                                        put("lastTimeUsedEpochMillis", usage.lastTimeUsedEpochMillis)
                                    }
                                    put("totalTimeForegroundMillis", usage?.totalTimeForegroundMillis ?: 0L)
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
        "apps.usage accepts only optional integer days from 1 to 365.",
    )

    private fun failure(requestId: String, code: String, message: String) =
        CommandResultPayload(
            requestId = requestId,
            ok = false,
            error = ProtocolError(code, message),
        )

    companion object {
        const val TOOL_NAME = "apps.usage"
        const val DEFAULT_DAYS = 30
        private const val DAYS = "days"
    }
}
