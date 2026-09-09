package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.protocol.CommandRequestPayload
import io.github.arttvad9r.hermesbridge.protocol.CommandResultPayload
import io.github.arttvad9r.hermesbridge.protocol.ProtocolError
import io.github.arttvad9r.hermesbridge.security.ApprovalDecision
import io.github.arttvad9r.hermesbridge.security.BridgeTool
import io.github.arttvad9r.hermesbridge.security.DefaultToolPolicy
import io.github.arttvad9r.hermesbridge.security.ToolRisk
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

class BridgeToolRegistry(
    private val healthRepository: DeviceHealthRepository,
    private val appsRepository: InstalledAppsRepository,
    private val filesRepository: SafFilesRepository,
    private val privilegedAppsBackend: PrivilegedAppsBackend = DisabledPrivilegedAppsBackend,
    private val apkArtifactRepository: ApkArtifactRepository? = null,
    private val apkPackageInspector: ApkPackageInspector? = null,
) {
    suspend fun execute(request: CommandRequestPayload): CommandResultPayload {
        return when (request.tool) {
            DEVICE_HEALTH -> executeDeviceHealth(request)
            APPS_LIST -> executeAppsList(request)
            FILES_LIST -> executeFilesList(request)
            FILES_ANALYZE -> executeFilesAnalyze(request)
            FILES_DELETE -> executeFilesDelete(request)
            APPS_INSTALL -> executeAppsInstall(request)
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
        val pathSegments = parseOnlyPathSegments(request, allowEmpty = true)
            ?: return invalidPathArguments(request, "files.list")

        val listing = try {
            filesRepository.list(pathSegments)
        } catch (error: Throwable) {
            return fileFailure(request, error, "files.list")
        }

        return CommandResultPayload(
            requestId = request.requestId,
            ok = true,
            result = buildJsonObject {
                put("rootName", listing.rootName)
                put("pathSegments", pathArray(listing.pathSegments))
                put("count", listing.entries.size)
                put(
                    "entries",
                    buildJsonArray {
                        listing.entries.forEach { entry ->
                            add(
                                buildJsonObject {
                                    put("name", entry.name)
                                    put("pathSegments", pathArray(entry.pathSegments))
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

    private fun executeFilesAnalyze(request: CommandRequestPayload): CommandResultPayload {
        if (!isReadOnlyAllowed(FILES_ANALYZE)) {
            return failure(request.requestId, "policy_denied", "Local policy did not allow the tool.")
        }
        val pathSegments = parseOnlyPathSegments(request, allowEmpty = true)
            ?: return invalidPathArguments(request, "files.analyze")

        val analysis = try {
            filesRepository.analyze(pathSegments)
        } catch (error: Throwable) {
            return fileFailure(request, error, "files.analyze")
        }

        return CommandResultPayload(
            requestId = request.requestId,
            ok = true,
            result = buildJsonObject {
                put("rootName", analysis.rootName)
                put("pathSegments", pathArray(analysis.pathSegments))
                put("scannedEntries", analysis.scannedEntries)
                put("fileCount", analysis.fileCount)
                put("directoryCount", analysis.directoryCount)
                put("totalBytes", analysis.totalBytes)
                put("truncated", analysis.truncated)
                put(
                    "largestFiles",
                    buildJsonArray {
                        analysis.largestFiles.forEach { file ->
                            add(
                                buildJsonObject {
                                    put("name", file.name)
                                    put("pathSegments", pathArray(file.pathSegments))
                                    if (file.mimeType == null) put("mimeType", JsonNull)
                                    else put("mimeType", file.mimeType)
                                    put("sizeBytes", file.sizeBytes)
                                    if (file.lastModifiedEpochMillis == null) {
                                        put("lastModifiedEpochMillis", JsonNull)
                                    } else {
                                        put("lastModifiedEpochMillis", file.lastModifiedEpochMillis)
                                    }
                                }
                            )
                        }
                    },
                )
            },
        )
    }

    private fun executeFilesDelete(request: CommandRequestPayload): CommandResultPayload {
        val pathSegments = parseOnlyPathSegments(request, allowEmpty = false)
            ?: return invalidPathArguments(request, "files.delete")

        val target = try {
            filesRepository.stat(pathSegments)
        } catch (error: Throwable) {
            return fileFailure(request, error, "files.delete")
        }

        val normalizedArguments = buildJsonObject {
            put(PATH_SEGMENTS, pathArray(target.pathSegments))
            put("directory", target.directory)
            if (target.mimeType == null) put("mimeType", JsonNull)
            else put("mimeType", target.mimeType)
            if (target.sizeBytes == null) put("sizeBytes", JsonNull)
            else put("sizeBytes", target.sizeBytes)
            if (target.lastModifiedEpochMillis == null) put("lastModifiedEpochMillis", JsonNull)
            else put("lastModifiedEpochMillis", target.lastModifiedEpochMillis)
        }
        requireApproval(
            request = request,
            tool = FILES_DELETE,
            risk = ToolRisk.MUTATING,
            normalizedArguments = normalizedArguments,
            summary = if (target.directory) {
                "Удалить папку ${target.pathSegments.joinToString("/")}"
            } else {
                "Удалить файл ${target.pathSegments.joinToString("/")}"
            },
        )?.let { return it }

        try {
            filesRepository.delete(pathSegments)
        } catch (error: Throwable) {
            return fileFailure(request, error, "files.delete")
        }

        return CommandResultPayload(
            requestId = request.requestId,
            ok = true,
            result = buildJsonObject {
                put(PATH_SEGMENTS, pathArray(pathSegments))
                put("directory", target.directory)
                put("deleted", true)
            },
        )
    }

    private suspend fun executeAppsInstall(request: CommandRequestPayload): CommandResultPayload {
        val allowedKeys = setOf(
            ARTIFACT_ID,
            DOWNLOAD_TOKEN,
            FILE_NAME,
            SIZE_BYTES,
            SHA256,
            REPLACE,
        )
        if (request.arguments.keys.any { it !in allowedKeys }) {
            return failure(
                request.requestId,
                "invalid_arguments",
                "apps.install accepts only staged APK artifact metadata and optional replace.",
            )
        }

        val descriptor = parseApkArtifactDescriptor(request)
            ?: return failure(
                request.requestId,
                "invalid_arguments",
                "APK artifact metadata is missing or invalid.",
            )
        val replace = when (val value = request.arguments[REPLACE]) {
            null -> true
            is JsonPrimitive -> value.booleanOrNull
                ?: return failure(request.requestId, "invalid_arguments", "replace must be a boolean.")
            else -> return failure(request.requestId, "invalid_arguments", "replace must be a boolean.")
        }

        try {
            RelayApkArtifactRepository.validateDescriptor(descriptor)
        } catch (_: IllegalArgumentException) {
            return failure(
                request.requestId,
                "invalid_arguments",
                "APK artifact metadata failed validation.",
            )
        }

        requirePrivilegedBackend(request)?.let { return it }
        val repository = apkArtifactRepository
            ?: return failure(
                request.requestId,
                "apk_install_unavailable",
                "APK artifact downloading is not configured.",
            )
        val inspector = apkPackageInspector
            ?: return failure(
                request.requestId,
                "apk_install_unavailable",
                "APK package inspection is not configured.",
            )

        val artifact = try {
            repository.download(descriptor)
        } catch (error: CancellationException) {
            throw error
        } catch (error: ApkArtifactDownloadException) {
            return failure(
                request.requestId,
                "apk_download_failed",
                error.message ?: "The staged APK could not be downloaded.",
            )
        } catch (error: Throwable) {
            return failure(
                request.requestId,
                "apk_download_failed",
                (error.message ?: "The staged APK could not be downloaded.").take(200),
            )
        }

        val metadata = try {
            inspector.inspect(artifact)
        } catch (error: ApkInspectionException) {
            return failure(
                request.requestId,
                "invalid_apk",
                error.message ?: "Android could not inspect the APK.",
            )
        } catch (error: Throwable) {
            return failure(
                request.requestId,
                "invalid_apk",
                (error.message ?: "Android could not inspect the APK.").take(200),
            )
        }

        if (!isValidPackageName(metadata.packageName)) {
            return failure(
                request.requestId,
                "invalid_apk",
                "APK contains an invalid package name.",
            )
        }
        if (metadata.packageName == HERMES_BRIDGE_PACKAGE) {
            return failure(
                request.requestId,
                "protected_package",
                "Hermes Bridge cannot replace itself through the agent install tool.",
            )
        }

        val normalizedArguments = buildJsonObject {
            put(SHA256, artifact.sha256)
            put(SIZE_BYTES, artifact.sizeBytes)
            put(PACKAGE_NAME, metadata.packageName)
            if (metadata.versionName == null) put(VERSION_NAME, JsonNull)
            else put(VERSION_NAME, metadata.versionName)
            put(VERSION_CODE, metadata.versionCode)
            put(
                SIGNER_SHA256,
                buildJsonArray {
                    metadata.signerSha256.sorted().forEach { add(JsonPrimitive(it)) }
                },
            )
            put(REPLACE, replace)
        }
        val versionLabel = metadata.versionName?.takeIf { it.isNotBlank() }
            ?: metadata.versionCode.toString()
        requireApproval(
            request = request,
            tool = APPS_INSTALL,
            risk = ToolRisk.MUTATING,
            normalizedArguments = normalizedArguments,
            summary = "Установить ${metadata.packageName} $versionLabel из ${artifact.fileName}",
        )?.let { return it }

        val outcome = privilegedAppsBackend.install(artifact, replace)
        if (!outcome.ok) {
            return failure(
                request.requestId,
                outcome.code ?: "install_failed",
                outcome.message ?: "The APK could not be installed.",
            )
        }

        return CommandResultPayload(
            requestId = request.requestId,
            ok = true,
            result = buildJsonObject {
                put(PACKAGE_NAME, metadata.packageName)
                if (metadata.versionName == null) put(VERSION_NAME, JsonNull)
                else put(VERSION_NAME, metadata.versionName)
                put(VERSION_CODE, metadata.versionCode)
                put(SHA256, artifact.sha256)
                put(REPLACE, replace)
                put("installed", true)
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
        val packageName = parsePackageName(request) ?: return packageNameFailure(request)
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
        val packageName = parsePackageName(request) ?: return packageNameFailure(request)
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

    private fun parseOnlyPathSegments(
        request: CommandRequestPayload,
        allowEmpty: Boolean,
    ): List<String>? {
        if (request.arguments.keys.any { it != PATH_SEGMENTS }) return null
        val value = request.arguments[PATH_SEGMENTS] ?: return if (allowEmpty) emptyList() else null
        if (value !is JsonArray) return null
        val parsed = value.map { element ->
            val primitive = element as? JsonPrimitive ?: return null
            if (!primitive.isString) return null
            primitive.content
        }
        if (!allowEmpty && parsed.isEmpty()) return null
        return runCatching {
            AndroidSafFilesRepository.validatePathSegments(parsed)
            parsed
        }.getOrNull()
    }

    private fun invalidPathArguments(
        request: CommandRequestPayload,
        tool: String,
    ) = failure(
        request.requestId,
        "invalid_arguments",
        "$tool accepts only a validated pathSegments array inside the granted SAF tree.",
    )

    private fun fileFailure(
        request: CommandRequestPayload,
        error: Throwable,
        tool: String,
    ): CommandResultPayload = when (error) {
        is FileAccessNotConfiguredException -> failure(
            request.requestId,
            "file_access_not_configured",
            "Choose a folder in the Hermes Bridge app before using $tool.",
        )
        is FilePathNotFoundException -> failure(
            request.requestId,
            "path_not_found",
            "The requested path does not exist inside the granted folder.",
        )
        is FilePathNotDirectoryException -> failure(
            request.requestId,
            "not_directory",
            "A directory component of the requested path is not a directory.",
        )
        is FileDeleteFailedException -> failure(
            request.requestId,
            "delete_failed",
            "The Android document provider refused to delete the requested target.",
        )
        is UnsupportedOperationException -> failure(
            request.requestId,
            "file_operation_unavailable",
            "The requested file operation is unavailable in this build.",
        )
        is SecurityException -> failure(
            request.requestId,
            "file_access_revoked",
            error.message ?: "The persisted SAF permission is no longer available.",
        )
        else -> failure(
            request.requestId,
            "file_operation_failed",
            (error.message ?: "The file operation failed.").take(200),
        )
    }

    private fun pathArray(pathSegments: List<String>): JsonArray = buildJsonArray {
        pathSegments.forEach { add(JsonPrimitive(it)) }
    }

    private fun parseApkArtifactDescriptor(request: CommandRequestPayload): ApkArtifactDescriptor? {
        fun string(name: String): String? {
            val value = request.arguments[name] as? JsonPrimitive ?: return null
            return value.takeIf { it.isString }?.content
        }

        val sizePrimitive = request.arguments[SIZE_BYTES] as? JsonPrimitive ?: return null
        val size = sizePrimitive.longOrNull ?: return null
        return ApkArtifactDescriptor(
            artifactId = string(ARTIFACT_ID) ?: return null,
            downloadToken = string(DOWNLOAD_TOKEN) ?: return null,
            fileName = string(FILE_NAME) ?: return null,
            sizeBytes = size,
            sha256 = string(SHA256) ?: return null,
        )
    }

    private fun parsePackageName(request: CommandRequestPayload): String? {
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
        normalizedArguments: JsonObject,
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
        const val FILES_ANALYZE = "files.analyze"
        const val FILES_DELETE = "files.delete"
        const val APPS_INSTALL = "apps.install"
        const val APPS_UNINSTALL = "apps.uninstall"
        const val APPS_FORCE_STOP = "apps.forceStop"

        private const val PATH_SEGMENTS = "pathSegments"
        private const val PACKAGE_NAME = "packageName"
        private const val KEEP_DATA = "keepData"
        private const val ARTIFACT_ID = "artifactId"
        private const val DOWNLOAD_TOKEN = "downloadToken"
        private const val FILE_NAME = "fileName"
        private const val SIZE_BYTES = "sizeBytes"
        private const val SHA256 = "sha256"
        private const val REPLACE = "replace"
        private const val VERSION_NAME = "versionName"
        private const val VERSION_CODE = "versionCode"
        private const val SIGNER_SHA256 = "signerSha256"
        private const val HERMES_BRIDGE_PACKAGE = "io.github.arttvad9r.hermesbridge"
        private val PACKAGE_NAME_REGEX = Regex(
            "^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$"
        )
    }
}
