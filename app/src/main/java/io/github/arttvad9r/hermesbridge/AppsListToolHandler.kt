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

/**
 * Remote-only projection of the launcher-visible app set.
 *
 * The underlying repository may retain a larger set for local visibility checks. This projection is
 * deliberately smaller and string-bounded so list-like command results stay comfortably below the
 * relay WebSocket frame limit even for adversarial application labels/version strings.
 */
internal data class RemoteAppsProjection(
    val totalVisibleCount: Int,
    val apps: List<InstalledAppSnapshot>,
    val truncated: Boolean,
)

internal fun projectAppsForRemoteResult(apps: List<InstalledAppSnapshot>): RemoteAppsProjection {
    val projected = apps.asSequence()
        .filter { isRemoteSafePackageName(it.packageName) }
        .take(MAX_REMOTE_APP_ENTRIES)
        .map { app ->
            app.copy(
                label = boundedRemoteText(app.label, MAX_REMOTE_APP_LABEL_CHARS),
                versionName = app.versionName?.let {
                    boundedRemoteText(it, MAX_REMOTE_APP_VERSION_NAME_CHARS)
                },
            )
        }
        .toList()
    return RemoteAppsProjection(
        totalVisibleCount = apps.size,
        apps = projected,
        truncated = projected.size < apps.size,
    )
}

class AppsListToolHandler(
    private val appsRepository: InstalledAppsRepository,
) {
    fun execute(request: CommandRequestPayload): CommandResultPayload {
        if (request.arguments.isNotEmpty()) {
            return failure(
                request.requestId,
                "invalid_arguments",
                "apps.list does not accept arguments.",
            )
        }
        if (
            DefaultToolPolicy.decision(BridgeTool(TOOL_NAME, ToolRisk.READ_ONLY)) !=
            ApprovalDecision.ALLOW
        ) {
            return failure(request.requestId, "policy_denied", "Local policy did not allow the tool.")
        }

        val projection = projectAppsForRemoteResult(appsRepository.listLaunchableApps())
        return CommandResultPayload(
            requestId = request.requestId,
            ok = true,
            result = buildJsonObject {
                put("count", projection.apps.size)
                put("totalVisibleCount", projection.totalVisibleCount)
                put("truncated", projection.truncated)
                put(
                    "apps",
                    buildJsonArray {
                        projection.apps.forEach { app ->
                            add(
                                buildJsonObject {
                                    put("packageName", app.packageName)
                                    put("label", app.label)
                                    if (app.versionName == null) put("versionName", JsonNull)
                                    else put("versionName", app.versionName)
                                    put("versionCode", app.versionCode)
                                    put("systemApp", app.systemApp)
                                    put("enabled", app.enabled)
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
        const val TOOL_NAME = BridgeToolRegistry.APPS_LIST
    }
}

internal fun boundedRemoteText(value: String, maxChars: Int): String {
    require(maxChars > 0)
    return value
        .asSequence()
        .map { character -> if (character.isISOControl()) ' ' else character }
        .take(maxChars)
        .joinToString(separator = "")
}

private fun isRemoteSafePackageName(value: String): Boolean =
    value.length in 3..MAX_REMOTE_PACKAGE_NAME_CHARS && REMOTE_PACKAGE_NAME_REGEX.matches(value)

internal const val MAX_REMOTE_APP_ENTRIES = 128
internal const val MAX_REMOTE_APP_LABEL_CHARS = 120
internal const val MAX_REMOTE_APP_VERSION_NAME_CHARS = 80
private const val MAX_REMOTE_PACKAGE_NAME_CHARS = 255
private val REMOTE_PACKAGE_NAME_REGEX = Regex(
    "^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$"
)
