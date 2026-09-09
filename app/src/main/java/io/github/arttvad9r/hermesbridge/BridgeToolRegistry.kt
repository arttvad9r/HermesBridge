package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.protocol.CommandRequestPayload
import io.github.arttvad9r.hermesbridge.protocol.CommandResultPayload
import io.github.arttvad9r.hermesbridge.protocol.ProtocolError
import io.github.arttvad9r.hermesbridge.security.ApprovalDecision
import io.github.arttvad9r.hermesbridge.security.BridgeTool
import io.github.arttvad9r.hermesbridge.security.DefaultToolPolicy
import io.github.arttvad9r.hermesbridge.security.ToolRisk
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class BridgeToolRegistry(
    private val healthRepository: DeviceHealthRepository,
    private val appsRepository: InstalledAppsRepository,
    private val filesRepository: SafFilesRepository,
    private val privilegedAppsBackend: PrivilegedAppsBackend = DisabledPrivilegedAppsBackend,
) {
    suspend fun execute(request: CommandRequestPayload): CommandResultPayload {
        return when (request.tool) {
            DEVICE_HEALTH -> executeDeviceHealth(request)
            APPS_LIST -> executeAppsList(request)
            FILES_LIST -> executeFilesList(request)
            APPS_UNINSTALL -> executeAppsUninstall(request)
            APPS_FORCE_STOP -> executeAppsForceStop(request)
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
        if (!isReadOnlyAllowed(DEVICE_HEALTH)) {
            return failure(request.requestId, "policy_denied", "Local policy did not allow the tool.")
        }

        val health = healthRepository.snapshot()
        return CommandResultPayload(
            requestId = request.requestId,
            ok = true,
            result = buildJsonObject {
                if (health.batteryPercent == null) put("batteryPercent", JsonNull)
                else put("batteryPercent", health.batteryPercent)
                put("availableMemoryBytes", health.availableMemoryBytes)
                put("totalMemoryBytes", health.totalMemoryBytes)
                put("availableStorageBytes", health.availableStorageBytes)
                put("totalStorageBytes", health.totalStorageBytes)
            },
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
        if (!isReadOnlyAllowed(APPS_LIST)) {
            return failure(request.requestId, "policy_denied", "Local policy did not allow the tool.")
        }

        val apps = appsRepository.listLaunchableApps()
        return CommandResultPayload(
            requestId = request.requestId,
            ok = true,
            result = buildJsonObject {
                put("count", apps.size)
                put(
                    "apps",
                    buildJsonArray {
                        apps.forEach { app ->
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

    private fun executeFilesList(request: CommandRequestPayload): CommandResultPayload {
        if (!isReadOnlyAllowed(FILES_LIST)) {
            return failure(request.requestId, "policy_denied", "Local policy did not allow the tool.")
        }
        if (request.arguments.keys.any { it != PATH_SEGMENTS }) {
            return failure(
                request.requestId,
                "invalid_arguments",
                "files.list accepts only the optional pathSegments array.",
            )
        }

        val pathSegments = when (val value = request.arguments[PATH_SEGMENTS]) {
            null -> emptyList()
            is JsonArray -> {
                val parsed = value.map { element ->
                    val primitive = element as? JsonPrimitive
                        ?: return failure(
                            request.requestId,
                            "invalid_arguments",
                            "pathSegments must contain only strings.",
                        )
                    if (!primitive.isString) {
                        return failure(
                            request.requestId,
                            "invalid_arguments",
                            "pathSegments must contain only strings.",
                        )
                    }
                    primitive.content
                }
                runCatching { AndroidSafFilesRepository.validatePathSegments(parsed) }
                    .getOrElse {
                        return failure(
                            request.requestId,
                            "invalid_arguments",
                            it.message ?: "Invalid pathSegments.",
                        )
                    }
                parsed
            }
            else -> return failure(
                request.requestId,
                "invalid_arguments",
                "pathSegments must be an array of strings.",
            )
        }

        val listing = try {
            filesRepository.list(pathSegments)
        } catch (_: FileAccessNotConfiguredException) {
            return failure(
                request.requestId,
                "file_access_not_configured",
                "Choose a folder in the Hermes Bridge app before using files.list.",
            )
        } catch (_: FilePathNotFoundException) {
            return failure(
                request.requestId,
                "path_not_found",
                "The requested path does not exist inside the granted folder.",
            )
        } catch (_: FilePathNotDirectoryException) {
            return failure(request.requestId, "not_directory", "The requested path is not a directory.")
        } catch (error: SecurityException) {
            return failure(
                request.requestId,
                "file_access_revoked",
                error.message ?: "The persisted SAF permission is no longer available.",
            )
        }

        return CommandResultPayload(
            requestId = request.requestId,
            ok = true,
            result = buildJsonObject {
                put("rootName", listing.rootName)
                put(
                    "pathSegments",
                    buildJsonArray { listing.pathSegments.forEach { add(JsonPrimitive(it)) } },
                )
                put("count", listing.entries.size)
                put(
                    "entries",
                    buildJsonArray {
                        listing.entries.forEach { entry ->
                            add(
                                buildJsonObject {
                                    put("name", entry.name)
                                    put(
                                        "pathSegments",
                                        buildJsonArray {
                                            entry.pathSegments.forEach { add(JsonPrimitive(it)) }
                                        },
                                    )
                                    put("directory", entry.directory)
                                    if (entry.mimeType == null) put("mimeType", JsonNull)
                                    else put("mimeType", entry.mimeType)
                                    if (entry.sizeBytes == null) put("sizeBytes", JsonNull)
                                    else put("sizeBytes", entry.sizeBytes)
                                    if (entry.lastModifiedEpochMillis == null) {
                                        put("lastModifiedEpochMillis", JsonNull)
                                    } else {
                                        put("lastModifiedEpochMillis", entry.lastModifiedEpochMillis)
                                    }
                                }
                            )
                        }
                    },
                )
            },
        )
    }

    private suspend fun executeAppsUninstall(request: CommandRequestPayload): CommandResultPayload {
        if (request.arguments.keys.any { it != PACKAGE_NAME && it != KEEP_DATA }) {
            return failure(
                request.requestId,
                "invalid_arguments",
                "apps.uninstall accepts only packageName and optional keepData.",
            )
        }
        val packageName = parsePackageName(request, APPS_UNINSTALL) ?: return packageNameFailure(request)
        if (packageName == HERMES_BRIDGE_PACKAGE) {
            return failure(
                request.requestId,
                "protected_package",
                "Hermes Bridge cannot uninstall itself.",
            )
        }

        val keepData = when (val value = request.arguments[KEEP_DATA]) {
            null -> false
            is JsonPrimitive -> value.booleanOrNull
                ?: return failure(request.requestId, "invalid_arguments", "keepData must be a boolean.")
            else -> return failure(request.requestId, "invalid_arguments", "keepData must be a boolean.")
        }
        val normalizedArguments = buildJsonObject {
            put(PACKAGE_NAME, packageName)
            put(KEEP_DATA, keepData)
        }
        requirePrivilegedBackend(request)?.let { return it }
        requireApproval(
            request = request,
            tool = APPS_UNINSTALL,
            risk = ToolRisk.MUTATING,
            normalizedArguments = normalizedArguments,
            summary = if (keepData) {
                "Удалить $packageName, сохранив данные приложения"
            } else {
                "Удалить $packageName и его данные"
            },
        )?.let { return it }

        val outcome = privilegedAppsBackend.uninstall(packageName, keepData)
        if (!outcome.ok) {
            return failure(
                request.requestId,
                outcome.code ?: "uninstall_failed",
                outcome.message ?: "The app could not be uninstalled.",
            )
        }
        return CommandResultPayload(
            requestId = request.requestId,
            ok = true,
            result = buildJsonObject {
                put(PACKAGE_NAME, packageName)
                put(KEEP_DATA, keepData)
                put("uninstalled", true)
            },
        )
    }

    private suspend fun executeAppsForceStop(request: CommandRequestPayload): CommandResultPayload {
        if (request.arguments.keys.any { it != PACKAGE_NAME }) {
            return failure(
                request.requestId,
                "invalid_arguments",
                "apps.forceStop accepts only packageName.",
            )
        }
        val packageName = parsePackageName(request, APPS_FORCE_STOP) ?: return packageNameFailure(request)
        if (packageName == HERMES_BRIDGE_PACKAGE) {
            return failure(
                request.requestId,
                "protected_package",
                "Hermes Bridge cannot force-stop itself because that would terminate the agent connection.",
            )
        }

        val normalizedArguments = buildJsonObject { put(PACKAGE_NAME, packageName) }
        requirePrivilegedBackend(request)?.let { return it }
        requireApproval(
            request = request,
            tool = APPS_FORCE_STOP,
            risk = ToolRisk.PRIVILEGED,
            normalizedArguments = normalizedArguments,
            summary = "Принудительно остановить $packageName",
        )?.let { return it }

        val outcome = privilegedAppsBackend.forceStop(packageName)
        if (!outcome.ok) {
            return failure(
                request.requestId,
                outcome.code ?: "force_stop_failed",
                outcome.message ?: "The app could not be force-stopped.",
            )
        }
        return CommandResultPayload(
            requestId = request.requestId,
            ok = true,
            result = buildJsonObject {
                put(PACKAGE_NAME, packageName)
                put("forceStopped", true)
            },
        )
    }

    private fun parsePackageName(request: CommandRequestPayload, tool: String): String? {
        val primitive = request.arguments[PACKAGE_NAME] as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        val packageName = primitive.content.trim()
        return packageName.takeIf(::isValidPackageName)
    }

    private fun packageNameFailure(request: CommandRequestPayload) = failure(
        request.requestId,
        "invalid_arguments",
        "packageName is required and must be a valid Android package name.",
    )

    private fun requirePrivilegedBackend(request: CommandRequestPayload): CommandResultPayload? {
        val readiness = privilegedAppsBackend.readiness()
        return if (readiness.ready) null else failure(
            request.requestId,
            readiness.code ?: "shizuku_unavailable",
            readiness.message ?: "Shizuku is not ready.",
        )
    }

    private fun requireApproval(
        request: CommandRequestPayload,
        tool: String,
        risk: ToolRisk,
        normalizedArguments: kotlinx.serialization.json.JsonObject,
        summary: String,
    ): CommandResultPayload? {
        if (DefaultToolPolicy.decision(BridgeTool(tool, risk)) != ApprovalDecision.REQUIRE_APPROVAL) {
            return failure(
                request.requestId,
                "policy_denied",
                "Local policy does not permit this privileged tool.",
            )
        }
        if (BridgeApprovalRuntime.consumeApproved(tool, normalizedArguments) != null) return null

        val ticket = BridgeApprovalRuntime.request(
            tool = tool,
            risk = risk,
            arguments = normalizedArguments,
            displaySummary = summary,
        )
        return failure(
            request.requestId,
            "approval_required",
            "User approval is required (${ticket.id}). Retry the same command after approval.",
        )
    }

    private fun isReadOnlyAllowed(toolName: String): Boolean =
        DefaultToolPolicy.decision(BridgeTool(toolName, ToolRisk.READ_ONLY)) == ApprovalDecision.ALLOW

    private fun isValidPackageName(value: String): Boolean =
        value.length in 3..255 && PACKAGE_NAME_REGEX.matches(value)

    private fun failure(requestId: String, code: String, message: String) =
        CommandResultPayload(
            requestId = requestId,
            ok = false,
            error = ProtocolError(code, message),
        )

    companion object {
        const val DEVICE_HEALTH = "device.health"
        const val APPS_LIST = "apps.list"
        const val FILES_LIST = "files.list"
        const val APPS_UNINSTALL = "apps.uninstall"
        const val APPS_FORCE_STOP = "apps.forceStop"

        private const val PATH_SEGMENTS = "pathSegments"
        private const val PACKAGE_NAME = "packageName"
        private const val KEEP_DATA = "keepData"
        private const val HERMES_BRIDGE_PACKAGE = "io.github.arttvad9r.hermesbridge"
        private val PACKAGE_NAME_REGEX = Regex(
            "^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$"
        )
    }
}
