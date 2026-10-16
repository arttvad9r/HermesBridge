package io.github.arttvad9r.typeddroid

import io.github.arttvad9r.typeddroid.adb.shellQuote
import io.github.arttvad9r.typeddroid.protocol.CommandRequestPayload
import io.github.arttvad9r.typeddroid.protocol.CommandResultPayload
import io.github.arttvad9r.typeddroid.protocol.ProtocolError
import io.github.arttvad9r.typeddroid.security.AgentTrustMode
import io.github.arttvad9r.typeddroid.security.ApprovalDecision
import io.github.arttvad9r.typeddroid.security.BridgeTool
import io.github.arttvad9r.typeddroid.security.DefaultToolPolicy
import io.github.arttvad9r.typeddroid.security.ToolRisk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Base64
import java.nio.file.Paths
import kotlinx.serialization.json.put

class BridgeToolRegistry(
    private val healthRepository: DeviceHealthRepository,
    private val appsRepository: InstalledAppsRepository,
    private val filesRepository: SafFilesRepository,
    private val privilegedAppsBackend: PrivilegedAppsBackend = DisabledPrivilegedAppsBackend,
    private val apkArtifactRepository: ApkArtifactRepository? = null,
    private val apkPackageInspector: ApkPackageInspector? = null,
    private val batteryDiagnosticsBackend: BatteryDiagnosticsBackend = DisabledBatteryDiagnosticsBackend,
    private val appUsageRepository: AppUsageRepository? = null,
    private val rawShellBackend: RawShellBackend = DisabledRawShellBackend,
    private val uiControlBackend: UiControlBackend = DisabledUiControlBackend,
    private val notificationControlBackend: NotificationControlBackend = DisabledNotificationControlBackend,
    private val agentTrustMode: () -> AgentTrustMode = { AgentTrustMode.SAFE },
) {
    suspend fun execute(request: CommandRequestPayload): CommandResultPayload {
        return when (request.tool) {
            DEVICE_HEALTH -> executeDeviceHealth(request)
            DEVICE_CAPABILITIES -> executeDeviceCapabilities(request)
            BATTERY_USAGE -> executeBatteryUsage(request)
            APPS_LIST -> executeAppsList(request)
            APPS_USAGE -> AppUsageToolHandler(appsRepository, appUsageRepository).execute(request)
            FILES_LIST -> executeFilesList(request)
            FILES_ANALYZE -> executeFilesAnalyze(request)
            FILES_DELETE -> executeFilesDelete(request)
            APPS_INSTALL -> executeAppsInstall(request)
            APPS_UNINSTALL -> executeAppsUninstall(request)
            APPS_FORCE_STOP -> executeAppsForceStop(request)
            APPS_OPEN -> executeAppsOpen(request)
            ACTIVITY_START -> executeActivityStart(request)
            SETTINGS_GET -> executeSettingsGet(request)
            SETTINGS_PUT -> executeSettingsPut(request)
            APPOPS_GET -> executeAppOpsGet(request)
            APPOPS_SET -> executeAppOpsSet(request)
            PROCESS_LIST -> executeProcessList(request)
            LOGCAT_READ -> executeLogcatRead(request)
            NOTIFICATIONS_LIST -> executeNotificationsList(request)
            NOTIFICATIONS_DISMISS -> executeNotificationDismiss(request)
            NOTIFICATIONS_OPEN -> executeNotificationOpen(request)
            NOTIFICATIONS_ACTION -> executeNotificationAction(request)
            NOTIFICATIONS_REPLY -> executeNotificationReply(request)
            FS_STAT -> executeFsStat(request)
            FS_LIST -> executeFsList(request)
            FS_READ -> executeFsRead(request)
            FS_WRITE -> executeFsWrite(request)
            FS_MKDIR -> executeFsMkdir(request)
            FS_COPY -> executeFsCopy(request)
            FS_MOVE -> executeFsMove(request)
            FS_DELETE -> executeFsDelete(request)
            FS_SEARCH -> executeFsSearch(request)
            UI_HIERARCHY -> executeUiHierarchy(request)
            UI_SCREENSHOT -> executeUiScreenshot(request)
            UI_TAP -> executeUiTap(request)
            UI_SWIPE -> executeUiSwipe(request)
            UI_LONG_PRESS -> executeUiLongPress(request)
            UI_WAIT_FOR_TEXT -> executeUiWaitForText(request)
            UI_TEXT -> executeUiText(request)
            UI_KEY -> executeUiKey(request)
            SHELL_RUN -> executeShellRun(request)
            else -> failure(
                request.requestId,
                "unknown_tool",
                "Tool is not present in the TypedDroid allowlist.",
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

    private fun executeDeviceCapabilities(request: CommandRequestPayload): CommandResultPayload {
        if (request.arguments.isNotEmpty()) {
            return failure(request.requestId, "invalid_arguments", "device.capabilities does not accept arguments.")
        }
        if (!isReadOnlyAllowed(DEVICE_CAPABILITIES)) {
            return failure(request.requestId, "policy_denied", "Local policy did not allow the tool.")
        }
        val trust = agentTrustMode()
        val shellReady = runCatching { rawShellBackend.readiness().ready }.getOrDefault(false)
        val uiReady = runCatching { uiControlBackend.readiness().ready }.getOrDefault(false)
        return CommandResultPayload(
            requestId = request.requestId,
            ok = true,
            result = buildJsonObject {
                put("agentTrustMode", trust.name.lowercase())
                put("embeddedPrivilegedReady", shellReady)
                put("rawShell", trust == AgentTrustMode.TRUSTED && shellReady)
                put("trustedSystemControl", trust == AgentTrustMode.TRUSTED && shellReady)
                put("uiControl", trust == AgentTrustMode.TRUSTED && uiReady)
                put("notificationControl", trust == AgentTrustMode.TRUSTED && notificationControlBackend.readiness().ready)
                put("structuredAppManagement", privilegedAppsBackend.readiness().ready)
                put("batteryDiagnostics", batteryDiagnosticsBackend.readiness().ready)
                put(
                    "trustedTools",
                    buildJsonArray {
                        listOf(
                            APPS_OPEN, ACTIVITY_START, SETTINGS_GET, SETTINGS_PUT, APPOPS_GET, APPOPS_SET,
                            PROCESS_LIST, LOGCAT_READ, NOTIFICATIONS_LIST, NOTIFICATIONS_DISMISS, NOTIFICATIONS_OPEN,
                            NOTIFICATIONS_ACTION, NOTIFICATIONS_REPLY, FS_STAT, FS_LIST, FS_READ, FS_WRITE, FS_MKDIR,
                            FS_COPY, FS_MOVE, FS_DELETE, FS_SEARCH, UI_HIERARCHY, UI_SCREENSHOT, UI_TAP, UI_SWIPE,
                            UI_LONG_PRESS, UI_WAIT_FOR_TEXT, UI_TEXT, UI_KEY, SHELL_RUN,
                        ).forEach { add(JsonPrimitive(it)) }
                    },
                )
            },
        )
    }

    private suspend fun executeBatteryUsage(request: CommandRequestPayload): CommandResultPayload {
        if (request.arguments.isNotEmpty()) {
            return failure(
                request.requestId,
                "invalid_arguments",
                "battery.usage does not accept arguments.",
            )
        }
        if (!isReadOnlyAllowed(BATTERY_USAGE)) {
            return failure(request.requestId, "policy_denied", "Local policy did not allow the tool.")
        }

        val readiness = batteryDiagnosticsBackend.readiness()
        if (!readiness.ready) {
            return failure(
                request.requestId,
                readiness.code ?: "embedded_privileged_unavailable",
                readiness.message ?: "Embedded privileged access is not ready for battery diagnostics.",
            )
        }

        val diagnostics = batteryDiagnosticsBackend.readUsage()
        if (!diagnostics.ok) {
            return failure(
                request.requestId,
                diagnostics.code ?: "battery_stats_failed",
                diagnostics.message ?: "Battery diagnostics failed.",
            )
        }
        val snapshot = diagnostics.snapshot
            ?: return failure(
                request.requestId,
                "battery_stats_failed",
                "Battery diagnostics returned no parsed snapshot.",
            )

        return CommandResultPayload(
            requestId = request.requestId,
            ok = true,
            result = buildJsonObject {
                put("source", "batterystats_charged_checkin")
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
                                    put(
                                        "packageNames",
                                        buildJsonArray {
                                            item.packageNames.forEach { add(JsonPrimitive(it)) }
                                        },
                                    )
                                    put("mah", item.mah)
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
                                    put(
                                        "packageNames",
                                        buildJsonArray {
                                            item.packageNames.forEach { add(JsonPrimitive(it)) }
                                        },
                                    )
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
                                }
                            )
                        }
                    },
                )
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
        if (metadata.packageName == TYPED_DROID_PACKAGE) {
            return failure(
                request.requestId,
                "protected_package",
                "TypedDroid cannot replace itself through the agent install tool.",
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
        if (packageName == TYPED_DROID_PACKAGE) {
            return failure(
                request.requestId,
                "protected_package",
                "TypedDroid cannot uninstall itself.",
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
        if (packageName == TYPED_DROID_PACKAGE) {
            return failure(
                request.requestId,
                "protected_package",
                "TypedDroid cannot force-stop itself because that would terminate the agent connection.",
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

    private suspend fun executeAppsOpen(request: CommandRequestPayload): CommandResultPayload {
        if (request.arguments.keys.any { it != PACKAGE_NAME }) {
            return failure(request.requestId, "invalid_arguments", "apps.open accepts only packageName.")
        }
        val packageName = parsePackageName(request) ?: return packageNameFailure(request)
        requireUiControl(request)?.let { return it }
        val result = uiControlBackend.openApp(packageName)
        return actionResult(request, result, "open_app_failed") {
            put(PACKAGE_NAME, packageName)
            put("opened", true)
        }
    }

    private suspend fun executeActivityStart(request: CommandRequestPayload): CommandResultPayload {
        val allowed = setOf(COMPONENT, ACTION, DATA_URI)
        if (request.arguments.keys.any { it !in allowed }) {
            return failure(request.requestId, "invalid_arguments", "activity.start accepts component, action and dataUri.")
        }
        val component = stringArgument(request, COMPONENT, MAX_COMPONENT_BYTES)
        val action = stringArgument(request, ACTION, MAX_ACTION_BYTES)
        val dataUri = stringArgument(request, DATA_URI, MAX_DATA_URI_BYTES)
        if (component == null && action == null) {
            return failure(request.requestId, "invalid_arguments", "activity.start requires component or action.")
        }
        if (component != null && !COMPONENT_REGEX.matches(component)) {
            return failure(request.requestId, "invalid_arguments", "component is not a valid Android component name.")
        }
        if (action != null && !ACTION_REGEX.matches(action)) {
            return failure(request.requestId, "invalid_arguments", "action is not a valid Android action name.")
        }
        if (dataUri != null && ('\n' in dataUri || '\r' in dataUri)) {
            return failure(request.requestId, "invalid_arguments", "dataUri contains forbidden control characters.")
        }
        requireTrustedControl(request)?.let { return it }
        val argv = mutableListOf("am", "start", "-W")
        if (component != null) { argv += "-n"; argv += component }
        if (action != null) { argv += "-a"; argv += action }
        if (dataUri != null) { argv += "-d"; argv += dataUri }
        val result = executeTrustedArgv(argv, TRUSTED_COMMAND_TIMEOUT_MILLIS)
        trustedCommandFailure(request, result, "activity_start_failed")?.let { return it }
        return CommandResultPayload(
            requestId = request.requestId,
            ok = true,
            result = buildJsonObject {
                put("started", true)
                put("output", result.stdout.decodeToString().trim().take(MAX_TRUSTED_TEXT_RESULT_CHARS))
            },
        )
    }

    private suspend fun executeSettingsGet(request: CommandRequestPayload): CommandResultPayload {
        if (request.arguments.keys != setOf(NAMESPACE, KEY)) {
            return failure(request.requestId, "invalid_arguments", "settings.get requires namespace and key.")
        }
        val namespace = settingsNamespace(request) ?: return trustedArgumentFailure(request)
        val key = settingsKey(request) ?: return trustedArgumentFailure(request)
        requireTrustedControl(request)?.let { return it }
        val result = executeTrustedArgv(listOf("settings", "get", namespace, key), TRUSTED_COMMAND_TIMEOUT_MILLIS)
        trustedCommandFailure(request, result, "settings_get_failed")?.let { return it }
        val value = result.stdout.decodeToString().trimEnd('\r', '\n')
        return CommandResultPayload(
            requestId = request.requestId,
            ok = true,
            result = buildJsonObject {
                put(NAMESPACE, namespace)
                put(KEY, key)
                if (value == "null") put("value", JsonNull) else put("value", value)
            },
        )
    }

    private suspend fun executeSettingsPut(request: CommandRequestPayload): CommandResultPayload {
        if (request.arguments.keys != setOf(NAMESPACE, KEY, VALUE)) {
            return failure(request.requestId, "invalid_arguments", "settings.put requires namespace, key and value.")
        }
        val namespace = settingsNamespace(request) ?: return trustedArgumentFailure(request)
        val key = settingsKey(request) ?: return trustedArgumentFailure(request)
        val value = stringArgument(request, VALUE, MAX_SETTING_VALUE_BYTES, allowEmpty = true)
            ?: return trustedArgumentFailure(request)
        requireTrustedControl(request)?.let { return it }
        val result = executeTrustedArgv(listOf("settings", "put", namespace, key, value), TRUSTED_COMMAND_TIMEOUT_MILLIS)
        trustedCommandFailure(request, result, "settings_put_failed")?.let { return it }
        return CommandResultPayload(
            requestId = request.requestId,
            ok = true,
            result = buildJsonObject { put(NAMESPACE, namespace); put(KEY, key); put("updated", true) },
        )
    }

    private suspend fun executeAppOpsGet(request: CommandRequestPayload): CommandResultPayload {
        if (request.arguments.keys.any { it != PACKAGE_NAME && it != OP_NAME } || PACKAGE_NAME !in request.arguments) {
            return failure(request.requestId, "invalid_arguments", "appops.get requires packageName and optional opName.")
        }
        val packageName = parsePackageName(request) ?: return packageNameFailure(request)
        val opName = if (OP_NAME in request.arguments) appOpName(request) ?: return trustedArgumentFailure(request) else null
        requireTrustedControl(request)?.let { return it }
        val argv = mutableListOf("appops", "get", packageName)
        if (opName != null) argv += opName
        val result = executeTrustedArgv(argv, TRUSTED_COMMAND_TIMEOUT_MILLIS)
        trustedCommandFailure(request, result, "appops_get_failed")?.let { return it }
        return CommandResultPayload(
            requestId = request.requestId,
            ok = true,
            result = buildJsonObject {
                put(PACKAGE_NAME, packageName)
                if (opName != null) put(OP_NAME, opName)
                put("output", result.stdout.decodeToString())
                put("truncated", result.stdoutTruncated)
            },
        )
    }

    private suspend fun executeAppOpsSet(request: CommandRequestPayload): CommandResultPayload {
        if (request.arguments.keys != setOf(PACKAGE_NAME, OP_NAME, MODE)) {
            return failure(request.requestId, "invalid_arguments", "appops.set requires packageName, opName and mode.")
        }
        val packageName = parsePackageName(request) ?: return packageNameFailure(request)
        if (packageName == TYPED_DROID_PACKAGE) {
            return failure(request.requestId, "protected_package", "TypedDroid app-ops cannot be modified by its connected agent.")
        }
        val opName = appOpName(request) ?: return trustedArgumentFailure(request)
        val mode = stringArgument(request, MODE, 32)?.lowercase()?.takeIf { it in APP_OP_MODES }
            ?: return trustedArgumentFailure(request)
        requireTrustedControl(request)?.let { return it }
        val result = executeTrustedArgv(listOf("appops", "set", packageName, opName, mode), TRUSTED_COMMAND_TIMEOUT_MILLIS)
        trustedCommandFailure(request, result, "appops_set_failed")?.let { return it }
        return CommandResultPayload(
            requestId = request.requestId,
            ok = true,
            result = buildJsonObject { put(PACKAGE_NAME, packageName); put(OP_NAME, opName); put(MODE, mode); put("updated", true) },
        )
    }

    private suspend fun executeProcessList(request: CommandRequestPayload): CommandResultPayload {
        if (request.arguments.isNotEmpty()) {
            return failure(request.requestId, "invalid_arguments", "process.list does not accept arguments.")
        }
        requireTrustedControl(request)?.let { return it }
        val result = executeTrustedArgv(listOf("ps", "-A", "-o", "USER,PID,PPID,NAME"), TRUSTED_COMMAND_TIMEOUT_MILLIS)
        trustedCommandFailure(request, result, "process_list_failed")?.let { return it }
        var omitted = false
        val rows = result.stdout.decodeToString().lineSequence().drop(1).mapNotNull { line ->
            val columns = line.trim().split(Regex("\\s+"), limit = 4)
            if (columns.size != 4) null else columns
        }.toList()
        if (rows.size > MAX_PROCESS_RESULTS) omitted = true
        return CommandResultPayload(
            requestId = request.requestId,
            ok = true,
            result = buildJsonObject {
                put("processes", buildJsonArray {
                    rows.take(MAX_PROCESS_RESULTS).forEach { columns ->
                        add(buildJsonObject {
                            put("user", columns[0])
                            put("pid", columns[1].toIntOrNull() ?: -1)
                            put("ppid", columns[2].toIntOrNull() ?: -1)
                            put("name", columns[3])
                        })
                    }
                })
                put("truncated", result.stdoutTruncated || omitted)
            },
        )
    }

    private suspend fun executeLogcatRead(request: CommandRequestPayload): CommandResultPayload {
        val allowed = setOf(LINES, BUFFER)
        if (request.arguments.keys.any { it !in allowed }) {
            return failure(request.requestId, "invalid_arguments", "logcat.read accepts optional lines and buffer.")
        }
        val lines = if (LINES in request.arguments) {
            (request.arguments[LINES] as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..MAX_LOGCAT_LINES }
                ?: return trustedArgumentFailure(request)
        } else DEFAULT_LOGCAT_LINES
        val buffer = if (BUFFER in request.arguments) {
            stringArgument(request, BUFFER, 16)?.lowercase()?.takeIf { it in LOGCAT_BUFFERS }
                ?: return trustedArgumentFailure(request)
        } else DEFAULT_LOGCAT_BUFFER
        requireTrustedControl(request)?.let { return it }
        val result = executeTrustedArgv(
            listOf("logcat", "-d", "-t", lines.toString(), "-v", "threadtime", "-b", buffer),
            LOGCAT_TIMEOUT_MILLIS,
        )
        trustedCommandFailure(request, result, "logcat_read_failed")?.let { return it }
        return CommandResultPayload(
            requestId = request.requestId,
            ok = true,
            result = buildJsonObject {
                put(LINES, lines)
                put(BUFFER, buffer)
                put("text", result.stdout.decodeToString())
                put("truncated", result.stdoutTruncated)
            },
        )
    }

    private suspend fun executeNotificationsList(request: CommandRequestPayload): CommandResultPayload {
        val allowed = setOf(LIMIT)
        if (request.arguments.keys.any { it !in allowed }) {
            return failure(request.requestId, "invalid_arguments", "notifications.list accepts optional limit.")
        }
        val limit = if (LIMIT in request.arguments) {
            (request.arguments[LIMIT] as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..MAX_NOTIFICATION_RESULTS }
                ?: return trustedArgumentFailure(request)
        } else MAX_NOTIFICATION_RESULTS
        requireNotificationControl(request)?.let { return it }
        val result = notificationControlBackend.list(limit)
        if (!result.ok) return failure(request.requestId, result.code ?: "notification_list_failed", result.message ?: "Notification list failed.")
        return CommandResultPayload(
            requestId = request.requestId,
            ok = true,
            result = buildJsonObject {
                put("untrustedContent", true)
                put("notifications", buildJsonArray {
                    result.notifications.forEach { item ->
                        add(buildJsonObject {
                            put(NOTIFICATION_KEY, item.key)
                            put(PACKAGE_NAME, item.packageName)
                            put("postTimeEpochMillis", item.postTimeEpochMillis)
                            if (item.title == null) put("title", JsonNull) else put("title", item.title)
                            if (item.text == null) put("text", JsonNull) else put("text", item.text)
                            if (item.subText == null) put("subText", JsonNull) else put("subText", item.subText)
                            if (item.category == null) put("category", JsonNull) else put("category", item.category)
                            if (item.channelId == null) put("channelId", JsonNull) else put("channelId", item.channelId)
                            if (item.groupKey == null) put("groupKey", JsonNull) else put("groupKey", item.groupKey)
                            put("ongoing", item.ongoing)
                            put("clearable", item.clearable)
                            put("contentIntentAvailable", item.contentIntentAvailable)
                            put("actions", buildJsonArray {
                                item.actions.forEach { action -> add(buildJsonObject {
                                    put(ACTION_INDEX, action.index)
                                    put("title", action.title)
                                    put("hasRemoteInput", action.hasRemoteInput)
                                    put("semanticAction", action.semanticAction)
                                    put("authenticationRequired", action.authenticationRequired)
                                }) }
                            })
                        })
                    }
                })
            },
        )
    }

    private suspend fun executeNotificationDismiss(request: CommandRequestPayload): CommandResultPayload =
        notificationAction(request, NOTIFICATIONS_DISMISS, setOf(NOTIFICATION_KEY)) { key, _ -> notificationControlBackend.dismiss(key) }

    private suspend fun executeNotificationOpen(request: CommandRequestPayload): CommandResultPayload =
        notificationAction(request, NOTIFICATIONS_OPEN, setOf(NOTIFICATION_KEY)) { key, _ -> notificationControlBackend.open(key) }

    private suspend fun executeNotificationAction(request: CommandRequestPayload): CommandResultPayload =
        notificationAction(request, NOTIFICATIONS_ACTION, setOf(NOTIFICATION_KEY, ACTION_INDEX)) { key, index ->
            notificationControlBackend.action(key, checkNotNull(index))
        }

    private suspend fun executeNotificationReply(request: CommandRequestPayload): CommandResultPayload {
        if (request.arguments.keys != setOf(NOTIFICATION_KEY, ACTION_INDEX, TEXT)) {
            return failure(request.requestId, "invalid_arguments", "notifications.reply requires notificationKey, actionIndex and text.")
        }
        val key = notificationKey(request) ?: return trustedArgumentFailure(request)
        val index = (request.arguments[ACTION_INDEX] as? JsonPrimitive)?.intOrNull?.takeIf { it in 0..MAX_NOTIFICATION_ACTION_INDEX }
            ?: return trustedArgumentFailure(request)
        val text = stringArgument(request, TEXT, MAX_NOTIFICATION_REPLY_BYTES) ?: return trustedArgumentFailure(request)
        requireNotificationControl(request)?.let { return it }
        val result = notificationControlBackend.reply(key, index, text)
        if (!result.ok) return failure(request.requestId, result.code ?: "notification_reply_failed", result.message ?: "Notification reply failed.")
        return CommandResultPayload(request.requestId, true, buildJsonObject { put("replied", true) })
    }

    private suspend fun notificationAction(
        request: CommandRequestPayload,
        tool: String,
        expectedKeys: Set<String>,
        block: suspend (String, Int?) -> PrivilegedOperationResult,
    ): CommandResultPayload {
        if (request.arguments.keys != expectedKeys) return failure(request.requestId, "invalid_arguments", "$tool arguments are invalid.")
        val key = notificationKey(request) ?: return trustedArgumentFailure(request)
        val index = if (ACTION_INDEX in expectedKeys) {
            (request.arguments[ACTION_INDEX] as? JsonPrimitive)?.intOrNull?.takeIf { it in 0..MAX_NOTIFICATION_ACTION_INDEX }
                ?: return trustedArgumentFailure(request)
        } else null
        requireNotificationControl(request)?.let { return it }
        val result = block(key, index)
        if (!result.ok) return failure(request.requestId, result.code ?: "notification_action_failed", result.message ?: "Notification action failed.")
        return CommandResultPayload(request.requestId, true, buildJsonObject {
            put("completed", true)
            put(NOTIFICATION_KEY, key)
        })
    }

    private fun notificationKey(request: CommandRequestPayload): String? =
        stringArgument(request, NOTIFICATION_KEY, MAX_NOTIFICATION_KEY_BYTES)

    private fun requireNotificationControl(request: CommandRequestPayload): CommandResultPayload? {
        if (DefaultToolPolicy.decision(BridgeTool(request.tool, ToolRisk.TRUSTED_CONTROL), agentTrustMode()) != ApprovalDecision.ALLOW) {
            return failure(request.requestId, "policy_denied", "Notification control is available only to an agent explicitly placed in Trusted mode on the phone.")
        }
        val readiness = notificationControlBackend.readiness()
        return if (readiness.ready) null else failure(
            request.requestId,
            readiness.code ?: "notification_access_unavailable",
            readiness.message ?: "Notification access is unavailable.",
        )
    }

    private suspend fun executeFsStat(request: CommandRequestPayload): CommandResultPayload {
        if (request.arguments.keys != setOf(PATH)) {
            return failure(request.requestId, "invalid_arguments", "fs.stat requires path.")
        }
        val path = trustedPath(request, PATH) ?: return trustedFileArgumentFailure(request)
        requireTrustedControl(request)?.let { return it }
        val result = executeTrustedArgv(listOf("stat", "-c", "%F|%s|%Y", path), TRUSTED_COMMAND_TIMEOUT_MILLIS)
        trustedCommandFailure(request, result, "fs_stat_failed")?.let { return it }
        val lines = result.stdout.decodeToString().trimEnd().split('|', limit = 3)
        if (lines.size != 3) return failure(request.requestId, "fs_stat_failed", "Android returned malformed file metadata.")
        return CommandResultPayload(
            requestId = request.requestId,
            ok = true,
            result = buildJsonObject {
                put(PATH, path)
                put("type", lines[0])
                put("sizeBytes", lines[1].toLongOrNull() ?: -1L)
                put("modifiedEpochSeconds", lines[2].toLongOrNull() ?: -1L)
            },
        )
    }

    private suspend fun executeFsList(request: CommandRequestPayload): CommandResultPayload {
        if (request.arguments.keys != setOf(PATH)) {
            return failure(request.requestId, "invalid_arguments", "fs.list requires path.")
        }
        val path = trustedPath(request, PATH) ?: return trustedFileArgumentFailure(request)
        requireTrustedControl(request)?.let { return it }
        val result = executeTrustedArgv(
            listOf("find", path, "-mindepth", "1", "-maxdepth", "1", "-print0"),
            TRUSTED_COMMAND_TIMEOUT_MILLIS,
        )
        trustedCommandFailure(request, result, "fs_list_failed")?.let { return it }
        val paths = parseNullSeparatedPaths(result.stdout)
        return CommandResultPayload(
            requestId = request.requestId,
            ok = true,
            result = buildJsonObject {
                put(PATH, path)
                put("entries", buildJsonArray {
                    paths.take(MAX_FS_RESULTS).forEach { child ->
                        add(buildJsonObject {
                            put("name", child.substringAfterLast('/'))
                            put(PATH, child)
                        })
                    }
                })
                put("truncated", result.stdoutTruncated || paths.size > MAX_FS_RESULTS)
            },
        )
    }

    private suspend fun executeFsRead(request: CommandRequestPayload): CommandResultPayload {
        val allowed = setOf(PATH, OFFSET, MAX_BYTES)
        if (request.arguments.keys.any { it !in allowed } || PATH !in request.arguments) {
            return failure(request.requestId, "invalid_arguments", "fs.read requires path and optional offset/maxBytes.")
        }
        val path = trustedPath(request, PATH) ?: return trustedFileArgumentFailure(request)
        val offset = parseLongArgument(request, OFFSET, 0L..MAX_FS_OFFSET) ?: if (OFFSET in request.arguments) return trustedFileArgumentFailure(request) else 0L
        val maxBytes = parseIntArgument(request, MAX_BYTES, 1..MAX_FS_READ_BYTES)
            ?: if (MAX_BYTES in request.arguments) return trustedFileArgumentFailure(request) else DEFAULT_FS_READ_BYTES
        requireTrustedControl(request)?.let { return it }
        val result = executeTrustedArgv(
            listOf("dd", "if=$path", "bs=1", "skip=$offset", "count=$maxBytes"),
            TRUSTED_COMMAND_TIMEOUT_MILLIS,
        )
        trustedCommandFailure(request, result, "fs_read_failed")?.let { return it }
        val bytes = result.stdout.take(MAX_FS_READ_BYTES).toByteArray()
        return CommandResultPayload(
            requestId = request.requestId,
            ok = true,
            result = buildJsonObject {
                put(PATH, path)
                put(OFFSET, offset)
                put("bytesRead", bytes.size)
                put("nextOffset", offset + bytes.size)
                put("base64", Base64.getEncoder().encodeToString(bytes))
                put("morePossible", bytes.size == maxBytes || result.stdoutTruncated)
            },
        )
    }

    private suspend fun executeFsWrite(request: CommandRequestPayload): CommandResultPayload {
        val allowed = setOf(PATH, DATA_BASE64, OFFSET, TRUNCATE, CREATE_PARENTS)
        if (request.arguments.keys.any { it !in allowed } || !request.arguments.keys.containsAll(setOf(PATH, DATA_BASE64))) {
            return failure(request.requestId, "invalid_arguments", "fs.write requires path and dataBase64 with optional offset/truncate/createParents.")
        }
        val path = trustedPath(request, PATH, mutation = true) ?: return trustedFileArgumentFailure(request)
        val encoded = stringArgument(request, DATA_BASE64, MAX_FS_WRITE_BASE64_BYTES, allowEmpty = true)
            ?: return trustedFileArgumentFailure(request)
        val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
            ?.takeIf { it.size <= MAX_FS_WRITE_BYTES }
            ?: return trustedFileArgumentFailure(request)
        val canonicalBase64 = Base64.getEncoder().encodeToString(bytes)
        val offset = parseLongArgument(request, OFFSET, 0L..MAX_FS_OFFSET) ?: if (OFFSET in request.arguments) return trustedFileArgumentFailure(request) else 0L
        val truncate = booleanArgument(request, TRUNCATE) ?: if (TRUNCATE in request.arguments) return trustedFileArgumentFailure(request) else offset == 0L
        val createParents = booleanArgument(request, CREATE_PARENTS) ?: if (CREATE_PARENTS in request.arguments) return trustedFileArgumentFailure(request) else false
        if (truncate && offset != 0L) return trustedFileArgumentFailure(request)
        requireTrustedControl(request)?.let { return it }
        val parent = Paths.get(path).parent?.toString()
        val prefix = if (createParents && parent != null) "mkdir -p ${shellQuote(parent)} && " else ""
        val decode = "printf '%s' ${shellQuote(canonicalBase64)} | base64 -d"
        val command = if (truncate) {
            "$prefix$decode > ${shellQuote(path)}"
        } else {
            "$prefix$decode | dd of=${shellQuote(path)} bs=1 seek=$offset conv=notrunc 2>/dev/null"
        }
        val result = rawShellBackend.execute(command, TRUSTED_COMMAND_TIMEOUT_MILLIS)
        trustedCommandFailure(request, result, "fs_write_failed")?.let { return it }
        return CommandResultPayload(
            requestId = request.requestId,
            ok = true,
            result = buildJsonObject {
                put(PATH, path)
                put("bytesWritten", bytes.size)
                put(OFFSET, offset)
                put(TRUNCATE, truncate)
            },
        )
    }

    private suspend fun executeFsMkdir(request: CommandRequestPayload): CommandResultPayload {
        val allowed = setOf(PATH, PARENTS)
        if (request.arguments.keys.any { it !in allowed } || PATH !in request.arguments) {
            return failure(request.requestId, "invalid_arguments", "fs.mkdir requires path and optional parents.")
        }
        val path = trustedPath(request, PATH, mutation = true) ?: return trustedFileArgumentFailure(request)
        val parents = booleanArgument(request, PARENTS) ?: if (PARENTS in request.arguments) return trustedFileArgumentFailure(request) else true
        requireTrustedControl(request)?.let { return it }
        val argv = if (parents) listOf("mkdir", "-p", path) else listOf("mkdir", path)
        val result = executeTrustedArgv(argv, TRUSTED_COMMAND_TIMEOUT_MILLIS)
        trustedCommandFailure(request, result, "fs_mkdir_failed")?.let { return it }
        return simpleFileActionResult(request, path, "created")
    }

    private suspend fun executeFsCopy(request: CommandRequestPayload): CommandResultPayload {
        val allowed = setOf(SOURCE_PATH, DESTINATION_PATH, RECURSIVE)
        if (request.arguments.keys.any { it !in allowed } || !request.arguments.keys.containsAll(setOf(SOURCE_PATH, DESTINATION_PATH))) {
            return failure(request.requestId, "invalid_arguments", "fs.copy requires sourcePath and destinationPath with optional recursive.")
        }
        val source = trustedPath(request, SOURCE_PATH) ?: return trustedFileArgumentFailure(request)
        val destination = trustedPath(request, DESTINATION_PATH, mutation = true) ?: return trustedFileArgumentFailure(request)
        val recursive = booleanArgument(request, RECURSIVE) ?: if (RECURSIVE in request.arguments) return trustedFileArgumentFailure(request) else false
        requireTrustedControl(request)?.let { return it }
        val argv = buildList { add("cp"); if (recursive) add("-R"); add("-f"); add(source); add(destination) }
        val result = executeTrustedArgv(argv, TRUSTED_COMMAND_TIMEOUT_MILLIS)
        trustedCommandFailure(request, result, "fs_copy_failed")?.let { return it }
        return CommandResultPayload(requestId = request.requestId, ok = true, result = buildJsonObject {
            put(SOURCE_PATH, source); put(DESTINATION_PATH, destination); put("copied", true)
        })
    }

    private suspend fun executeFsMove(request: CommandRequestPayload): CommandResultPayload {
        if (request.arguments.keys != setOf(SOURCE_PATH, DESTINATION_PATH)) {
            return failure(request.requestId, "invalid_arguments", "fs.move requires sourcePath and destinationPath.")
        }
        val source = trustedPath(request, SOURCE_PATH, mutation = true) ?: return trustedFileArgumentFailure(request)
        val destination = trustedPath(request, DESTINATION_PATH, mutation = true) ?: return trustedFileArgumentFailure(request)
        if (source in TRUSTED_MUTATION_ROOTS) {
            return failure(request.requestId, "protected_path", "Typed fs.move refuses to move a mutation root itself.")
        }
        requireTrustedControl(request)?.let { return it }
        val result = executeTrustedArgv(listOf("mv", "-f", source, destination), TRUSTED_COMMAND_TIMEOUT_MILLIS)
        trustedCommandFailure(request, result, "fs_move_failed")?.let { return it }
        return CommandResultPayload(requestId = request.requestId, ok = true, result = buildJsonObject {
            put(SOURCE_PATH, source); put(DESTINATION_PATH, destination); put("moved", true)
        })
    }

    private suspend fun executeFsDelete(request: CommandRequestPayload): CommandResultPayload {
        val allowed = setOf(PATH, RECURSIVE)
        if (request.arguments.keys.any { it !in allowed } || PATH !in request.arguments) {
            return failure(request.requestId, "invalid_arguments", "fs.delete requires path and optional recursive.")
        }
        val path = trustedPath(request, PATH, mutation = true) ?: return trustedFileArgumentFailure(request)
        if (path in TRUSTED_MUTATION_ROOTS) {
            return failure(request.requestId, "protected_path", "Typed fs.delete refuses to delete a mutation root itself.")
        }
        val recursive = booleanArgument(request, RECURSIVE) ?: if (RECURSIVE in request.arguments) return trustedFileArgumentFailure(request) else false
        requireTrustedControl(request)?.let { return it }
        val argv = if (recursive) listOf("rm", "-rf", path) else listOf("rm", "-f", path)
        val result = executeTrustedArgv(argv, TRUSTED_COMMAND_TIMEOUT_MILLIS)
        trustedCommandFailure(request, result, "fs_delete_failed")?.let { return it }
        return simpleFileActionResult(request, path, "deleted")
    }

    private suspend fun executeFsSearch(request: CommandRequestPayload): CommandResultPayload {
        val allowed = setOf(PATH, QUERY, MAX_DEPTH)
        if (request.arguments.keys.any { it !in allowed } || !request.arguments.keys.containsAll(setOf(PATH, QUERY))) {
            return failure(request.requestId, "invalid_arguments", "fs.search requires path and query with optional maxDepth.")
        }
        val path = trustedPath(request, PATH) ?: return trustedFileArgumentFailure(request)
        val query = stringArgument(request, QUERY, MAX_FS_QUERY_BYTES) ?: return trustedFileArgumentFailure(request)
        val depth = parseIntArgument(request, MAX_DEPTH, 1..MAX_FS_SEARCH_DEPTH)
            ?: if (MAX_DEPTH in request.arguments) return trustedFileArgumentFailure(request) else DEFAULT_FS_SEARCH_DEPTH
        requireTrustedControl(request)?.let { return it }
        val result = executeTrustedArgv(
            listOf("find", path, "-mindepth", "1", "-maxdepth", depth.toString(), "-print0"),
            TRUSTED_COMMAND_TIMEOUT_MILLIS,
        )
        trustedCommandFailure(request, result, "fs_search_failed")?.let { return it }
        val matches = parseNullSeparatedPaths(result.stdout).filter {
            it.substringAfterLast('/').contains(query, ignoreCase = true)
        }
        return CommandResultPayload(
            requestId = request.requestId,
            ok = true,
            result = buildJsonObject {
                put(PATH, path)
                put(QUERY, query)
                put("matches", buildJsonArray {
                    matches.take(MAX_FS_RESULTS).forEach { found ->
                        add(buildJsonObject { put("name", found.substringAfterLast('/')); put(PATH, found) })
                    }
                })
                put("truncated", result.stdoutTruncated || matches.size > MAX_FS_RESULTS)
            },
        )
    }

    private fun trustedPath(request: CommandRequestPayload, key: String, mutation: Boolean = false): String? {
        val raw = stringArgument(request, key, MAX_FS_PATH_BYTES) ?: return null
        if (!raw.startsWith('/') || '\n' in raw || '\r' in raw) return null
        val normalized = runCatching { Paths.get(raw).normalize().toString() }.getOrNull() ?: return null
        if (!normalized.startsWith('/')) return null
        if (mutation && TRUSTED_MUTATION_ROOTS.none { normalized == it || normalized.startsWith("$it/") }) return null
        return normalized
    }

    private fun parseNullSeparatedPaths(bytes: ByteArray): List<String> = bytes
        .splitOnZero()
        .mapNotNull { runCatching { it.decodeToString(throwOnInvalidSequence = true) }.getOrNull() }
        .filter { it.startsWith('/') && it.encodeToByteArray().size <= MAX_FS_PATH_BYTES }

    private fun ByteArray.splitOnZero(): List<ByteArray> {
        if (isEmpty()) return emptyList()
        val result = mutableListOf<ByteArray>()
        var start = 0
        for (index in indices) {
            if (this[index].toInt() == 0) {
                if (index > start) result += copyOfRange(start, index)
                start = index + 1
            }
        }
        if (start < size) result += copyOfRange(start, size)
        return result
    }

    private fun parseLongArgument(request: CommandRequestPayload, key: String, range: LongRange): Long? =
        (request.arguments[key] as? JsonPrimitive)?.longOrNull?.takeIf { it in range }

    private fun parseIntArgument(request: CommandRequestPayload, key: String, range: IntRange): Int? =
        (request.arguments[key] as? JsonPrimitive)?.intOrNull?.takeIf { it in range }

    private fun booleanArgument(request: CommandRequestPayload, key: String): Boolean? =
        (request.arguments[key] as? JsonPrimitive)?.booleanOrNull

    private fun trustedFileArgumentFailure(request: CommandRequestPayload) =
        failure(request.requestId, "invalid_arguments", "Trusted filesystem arguments are invalid or outside supported bounds.")

    private fun simpleFileActionResult(request: CommandRequestPayload, path: String, flag: String) =
        CommandResultPayload(requestId = request.requestId, ok = true, result = buildJsonObject {
            put(PATH, path); put(flag, true)
        })

    private suspend fun executeUiHierarchy(request: CommandRequestPayload): CommandResultPayload {
        if (request.arguments.isNotEmpty()) {
            return failure(request.requestId, "invalid_arguments", "ui.hierarchy does not accept arguments.")
        }
        requireUiControl(request)?.let { return it }
        val result = uiControlBackend.hierarchy()
        if (!result.ok) return failure(request.requestId, result.code ?: "ui_hierarchy_failed", result.message ?: "UI hierarchy failed.")
        return CommandResultPayload(
            requestId = request.requestId,
            ok = true,
            result = buildJsonObject { put("xml", checkNotNull(result.xml)) },
        )
    }

    private suspend fun executeUiScreenshot(request: CommandRequestPayload): CommandResultPayload {
        if (request.arguments.isNotEmpty()) {
            return failure(request.requestId, "invalid_arguments", "ui.screenshot does not accept arguments.")
        }
        requireUiControl(request)?.let { return it }
        val result = uiControlBackend.screenshot()
        if (!result.ok) return failure(request.requestId, result.code ?: "ui_screenshot_failed", result.message ?: "Screenshot failed.")
        return CommandResultPayload(
            requestId = request.requestId,
            ok = true,
            result = buildJsonObject {
                put("mimeType", checkNotNull(result.mimeType))
                put("width", checkNotNull(result.width))
                put("height", checkNotNull(result.height))
                put("base64", Base64.getEncoder().encodeToString(result.bytes))
            },
        )
    }

    private suspend fun executeUiTap(request: CommandRequestPayload): CommandResultPayload {
        if (request.arguments.keys != setOf(X, Y)) {
            return failure(request.requestId, "invalid_arguments", "ui.tap requires x and y.")
        }
        val x = parseUiInt(request, X, 0..MAX_UI_COORDINATE) ?: return uiArgumentFailure(request)
        val y = parseUiInt(request, Y, 0..MAX_UI_COORDINATE) ?: return uiArgumentFailure(request)
        requireUiControl(request)?.let { return it }
        return actionResult(request, uiControlBackend.tap(x, y), "ui_tap_failed") {
            put(X, x); put(Y, y); put("tapped", true)
        }
    }

    private suspend fun executeUiSwipe(request: CommandRequestPayload): CommandResultPayload {
        val allowed = setOf(START_X, START_Y, END_X, END_Y, DURATION_MILLIS)
        if (request.arguments.keys.any { it !in allowed } || !request.arguments.keys.containsAll(setOf(START_X, START_Y, END_X, END_Y))) {
            return failure(request.requestId, "invalid_arguments", "ui.swipe requires startX, startY, endX, endY and optional durationMillis.")
        }
        val sx = parseUiInt(request, START_X, 0..MAX_UI_COORDINATE) ?: return uiArgumentFailure(request)
        val sy = parseUiInt(request, START_Y, 0..MAX_UI_COORDINATE) ?: return uiArgumentFailure(request)
        val ex = parseUiInt(request, END_X, 0..MAX_UI_COORDINATE) ?: return uiArgumentFailure(request)
        val ey = parseUiInt(request, END_Y, 0..MAX_UI_COORDINATE) ?: return uiArgumentFailure(request)
        val duration = if (DURATION_MILLIS in request.arguments) {
            parseUiInt(request, DURATION_MILLIS, 1..MAX_UI_SWIPE_DURATION_MILLIS) ?: return uiArgumentFailure(request)
        } else DEFAULT_UI_SWIPE_DURATION_MILLIS
        requireUiControl(request)?.let { return it }
        return actionResult(request, uiControlBackend.swipe(sx, sy, ex, ey, duration), "ui_swipe_failed") {
            put("swiped", true)
        }
    }

    private suspend fun executeUiLongPress(request: CommandRequestPayload): CommandResultPayload {
        val allowed = setOf(X, Y, DURATION_MILLIS)
        if (request.arguments.keys.any { it !in allowed } || !request.arguments.keys.containsAll(setOf(X, Y))) {
            return failure(request.requestId, "invalid_arguments", "ui.longPress requires x, y and optional durationMillis.")
        }
        val x = parseUiInt(request, X, 0..MAX_UI_COORDINATE) ?: return uiArgumentFailure(request)
        val y = parseUiInt(request, Y, 0..MAX_UI_COORDINATE) ?: return uiArgumentFailure(request)
        val duration = if (DURATION_MILLIS in request.arguments) {
            parseUiInt(request, DURATION_MILLIS, MIN_UI_LONG_PRESS_DURATION_MILLIS..MAX_UI_LONG_PRESS_DURATION_MILLIS)
                ?: return uiArgumentFailure(request)
        } else DEFAULT_UI_LONG_PRESS_DURATION_MILLIS
        requireUiControl(request)?.let { return it }
        return actionResult(request, uiControlBackend.swipe(x, y, x, y, duration), "ui_long_press_failed") {
            put(X, x); put(Y, y); put(DURATION_MILLIS, duration); put("longPressed", true)
        }
    }

    private suspend fun executeUiWaitForText(request: CommandRequestPayload): CommandResultPayload {
        val allowed = setOf(TEXT, TIMEOUT_MILLIS, CASE_SENSITIVE)
        if (request.arguments.keys.any { it !in allowed } || TEXT !in request.arguments) {
            return failure(request.requestId, "invalid_arguments", "ui.waitForText requires text and optional timeoutMillis/caseSensitive.")
        }
        val text = stringArgument(request, TEXT, MAX_UI_WAIT_TEXT_BYTES)
            ?: return uiArgumentFailure(request)
        val timeout = if (TIMEOUT_MILLIS in request.arguments) {
            (request.arguments[TIMEOUT_MILLIS] as? JsonPrimitive)?.longOrNull
                ?.takeIf { it in 1..MAX_UI_WAIT_TIMEOUT_MILLIS }
                ?: return uiArgumentFailure(request)
        } else DEFAULT_UI_WAIT_TIMEOUT_MILLIS
        val caseSensitive = when (val value = request.arguments[CASE_SENSITIVE]) {
            null -> false
            is JsonPrimitive -> value.booleanOrNull ?: return uiArgumentFailure(request)
            else -> return uiArgumentFailure(request)
        }
        requireUiControl(request)?.let { return it }
        val started = System.nanoTime()
        while (true) {
            val result = uiControlBackend.hierarchy()
            if (!result.ok) {
                return failure(request.requestId, result.code ?: "ui_hierarchy_failed", result.message ?: "UI hierarchy failed.")
            }
            val xml = checkNotNull(result.xml)
            val escaped = xmlEscape(text)
            val matched = if (caseSensitive) {
                xml.contains(text) || xml.contains(escaped)
            } else {
                xml.contains(text, ignoreCase = true) || xml.contains(escaped, ignoreCase = true)
            }
            if (matched) {
                val elapsed = (System.nanoTime() - started) / 1_000_000
                return CommandResultPayload(
                    requestId = request.requestId,
                    ok = true,
                    result = buildJsonObject { put("matched", true); put("elapsedMillis", elapsed) },
                )
            }
            val elapsed = (System.nanoTime() - started) / 1_000_000
            if (elapsed >= timeout) {
                return failure(request.requestId, "ui_wait_timeout", "Text did not appear before timeout.")
            }
            delay(minOf(UI_WAIT_POLL_MILLIS, timeout - elapsed))
        }
    }

    private suspend fun executeUiText(request: CommandRequestPayload): CommandResultPayload {
        if (request.arguments.keys != setOf(TEXT)) return failure(request.requestId, "invalid_arguments", "ui.text requires text.")
        val primitive = request.arguments[TEXT] as? JsonPrimitive
        val text = primitive?.takeIf { it.isString }?.content
            ?.takeIf { it.isNotEmpty() && '\u0000' !in it && it.encodeToByteArray().size <= MAX_UI_TEXT_BYTES }
            ?: return uiArgumentFailure(request)
        requireUiControl(request)?.let { return it }
        return actionResult(request, uiControlBackend.text(text), "ui_text_failed") { put("typed", true) }
    }

    private suspend fun executeUiKey(request: CommandRequestPayload): CommandResultPayload {
        if (request.arguments.keys != setOf(KEY_CODE)) return failure(request.requestId, "invalid_arguments", "ui.key requires keyCode.")
        val keyCode = parseUiInt(request, KEY_CODE, 0..MAX_UI_KEY_CODE) ?: return uiArgumentFailure(request)
        requireUiControl(request)?.let { return it }
        return actionResult(request, uiControlBackend.key(keyCode), "ui_key_failed") {
            put(KEY_CODE, keyCode); put("sent", true)
        }
    }

    private fun requireUiControl(request: CommandRequestPayload): CommandResultPayload? {
        if (DefaultToolPolicy.decision(BridgeTool(request.tool, ToolRisk.UI_CONTROL), agentTrustMode()) != ApprovalDecision.ALLOW) {
            return failure(request.requestId, "policy_denied", "UI control is available only to an agent explicitly placed in Trusted mode on the phone.")
        }
        val readiness = uiControlBackend.readiness()
        return if (readiness.ready) null else failure(
            request.requestId,
            readiness.code ?: "embedded_privileged_unavailable",
            readiness.message ?: "Embedded privileged helper is not running.",
        )
    }

    private fun parseUiInt(request: CommandRequestPayload, key: String, range: IntRange): Int? =
        (request.arguments[key] as? JsonPrimitive)?.intOrNull?.takeIf { it in range }

    private fun uiArgumentFailure(request: CommandRequestPayload) =
        failure(request.requestId, "invalid_arguments", "UI control arguments are invalid or outside supported bounds.")

    private fun actionResult(
        request: CommandRequestPayload,
        result: PrivilegedOperationResult,
        fallbackCode: String,
        content: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): CommandResultPayload {
        if (!result.ok) return failure(request.requestId, result.code ?: fallbackCode, result.message ?: "Android UI command failed.")
        return CommandResultPayload(requestId = request.requestId, ok = true, result = buildJsonObject(content))
    }

    private fun requireTrustedControl(request: CommandRequestPayload): CommandResultPayload? {
        if (DefaultToolPolicy.decision(BridgeTool(request.tool, ToolRisk.TRUSTED_CONTROL), agentTrustMode()) != ApprovalDecision.ALLOW) {
            return failure(request.requestId, "policy_denied", "This system-control tool is available only to an agent explicitly placed in Trusted mode on the phone.")
        }
        val readiness = rawShellBackend.readiness()
        return if (readiness.ready) null else failure(
            request.requestId,
            readiness.code ?: "embedded_privileged_unavailable",
            readiness.message ?: "Embedded privileged helper is not running.",
        )
    }

    private suspend fun executeTrustedArgv(argv: List<String>, timeoutMillis: Long): RawShellExecutionResult {
        require(argv.isNotEmpty())
        return rawShellBackend.execute(argv.joinToString(" ") { shellQuote(it) }, timeoutMillis)
    }

    private fun trustedCommandFailure(
        request: CommandRequestPayload,
        result: RawShellExecutionResult,
        fallbackCode: String,
    ): CommandResultPayload? {
        if (!result.ok) {
            return failure(request.requestId, result.code ?: fallbackCode, result.message ?: "Trusted system command failed.")
        }
        if (result.exitCode != 0) {
            val detail = result.stderr.decodeToString().trim().ifEmpty { result.stdout.decodeToString().trim() }
                .take(MAX_TRUSTED_ERROR_CHARS)
            return failure(
                request.requestId,
                fallbackCode,
                detail.ifEmpty { "Trusted system command exited with status ${result.exitCode}." },
            )
        }
        return null
    }

    private fun stringArgument(
        request: CommandRequestPayload,
        key: String,
        maxBytes: Int,
        allowEmpty: Boolean = false,
    ): String? {
        val value = request.arguments[key] as? JsonPrimitive ?: return null
        if (!value.isString) return null
        val content = value.content
        if ((!allowEmpty && content.isEmpty()) || '\u0000' in content || content.encodeToByteArray().size > maxBytes) return null
        return content
    }

    private fun settingsNamespace(request: CommandRequestPayload): String? =
        stringArgument(request, NAMESPACE, 16)?.lowercase()?.takeIf { it in SETTINGS_NAMESPACES }

    private fun settingsKey(request: CommandRequestPayload): String? =
        stringArgument(request, KEY, MAX_SETTINGS_KEY_BYTES)?.takeIf { SETTINGS_KEY_REGEX.matches(it) }

    private fun appOpName(request: CommandRequestPayload): String? =
        stringArgument(request, OP_NAME, MAX_APP_OP_NAME_BYTES)?.takeIf { APP_OP_NAME_REGEX.matches(it) }

    private fun trustedArgumentFailure(request: CommandRequestPayload) =
        failure(request.requestId, "invalid_arguments", "Trusted system-control arguments are invalid or outside supported bounds.")

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private suspend fun executeShellRun(request: CommandRequestPayload): CommandResultPayload {
        if (request.arguments.keys.any { it != SHELL_COMMAND && it != TIMEOUT_MILLIS }) {
            return failure(
                request.requestId,
                "invalid_arguments",
                "shell.run accepts only command and optional timeoutMillis.",
            )
        }
        val commandValue = request.arguments[SHELL_COMMAND] as? JsonPrimitive
            ?: return failure(request.requestId, "invalid_arguments", "command is required.")
        if (!commandValue.isString) {
            return failure(request.requestId, "invalid_arguments", "command must be a string.")
        }
        val command = commandValue.content
        if (command.isEmpty() || '\u0000' in command || command.encodeToByteArray().size > MAX_SHELL_COMMAND_BYTES) {
            return failure(request.requestId, "invalid_arguments", "command is empty, too large, or contains NUL.")
        }
        val timeoutMillis = when (val value = request.arguments[TIMEOUT_MILLIS]) {
            null -> DEFAULT_SHELL_TIMEOUT_MILLIS
            is JsonPrimitive -> value.longOrNull
                ?.takeIf { it in 1..MAX_SHELL_TIMEOUT_MILLIS }
                ?: return failure(
                    request.requestId,
                    "invalid_arguments",
                    "timeoutMillis must be an integer from 1 to $MAX_SHELL_TIMEOUT_MILLIS.",
                )
            else -> return failure(request.requestId, "invalid_arguments", "timeoutMillis must be an integer.")
        }

        if (DefaultToolPolicy.decision(BridgeTool(SHELL_RUN, ToolRisk.RAW_SHELL), agentTrustMode()) != ApprovalDecision.ALLOW) {
            return failure(
                request.requestId,
                "policy_denied",
                "Raw shell is available only to an agent explicitly placed in Trusted mode on the phone.",
            )
        }

        val readiness = rawShellBackend.readiness()
        if (!readiness.ready) {
            return failure(
                request.requestId,
                readiness.code ?: "embedded_privileged_unavailable",
                readiness.message ?: "Embedded privileged helper is not running.",
            )
        }

        val result = rawShellBackend.execute(command, timeoutMillis)
        if (!result.ok) {
            return failure(
                request.requestId,
                result.code ?: "shell_failed",
                result.message ?: "Raw shell command failed before an exit status was available.",
            )
        }
        return CommandResultPayload(
            requestId = request.requestId,
            ok = true,
            result = buildJsonObject {
                put("exitCode", checkNotNull(result.exitCode))
                put("stdout", result.stdout.decodeToString())
                put("stderr", result.stderr.decodeToString())
                put("stdoutTruncated", result.stdoutTruncated)
                put("stderrTruncated", result.stderrTruncated)
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
            "Choose a folder in the TypedDroid app before using $tool.",
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
            readiness.code ?: "embedded_privileged_unavailable",
            readiness.message ?: "Embedded privileged access is not ready.",
        )
    }

    private fun requireApproval(
        request: CommandRequestPayload,
        tool: String,
        risk: ToolRisk,
        normalizedArguments: JsonObject,
        summary: String,
    ): CommandResultPayload? {
        if (DefaultToolPolicy.decision(BridgeTool(tool, risk), agentTrustMode()) != ApprovalDecision.REQUIRE_APPROVAL) {
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
        DefaultToolPolicy.decision(BridgeTool(toolName, ToolRisk.READ_ONLY), agentTrustMode()) == ApprovalDecision.ALLOW

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
        const val DEVICE_CAPABILITIES = "device.capabilities"
        const val BATTERY_USAGE = "battery.usage"
        const val APPS_LIST = "apps.list"
        const val APPS_USAGE = "apps.usage"
        const val FILES_LIST = "files.list"
        const val FILES_ANALYZE = "files.analyze"
        const val FILES_DELETE = "files.delete"
        const val APPS_INSTALL = "apps.install"
        const val APPS_UNINSTALL = "apps.uninstall"
        const val APPS_FORCE_STOP = "apps.forceStop"
        const val APPS_OPEN = "apps.open"
        const val ACTIVITY_START = "activity.start"
        const val SETTINGS_GET = "settings.get"
        const val SETTINGS_PUT = "settings.put"
        const val APPOPS_GET = "appops.get"
        const val APPOPS_SET = "appops.set"
        const val PROCESS_LIST = "process.list"
        const val LOGCAT_READ = "logcat.read"
        const val NOTIFICATIONS_LIST = "notifications.list"
        const val NOTIFICATIONS_DISMISS = "notifications.dismiss"
        const val NOTIFICATIONS_OPEN = "notifications.open"
        const val NOTIFICATIONS_ACTION = "notifications.action"
        const val NOTIFICATIONS_REPLY = "notifications.reply"
        const val FS_STAT = "fs.stat"
        const val FS_LIST = "fs.list"
        const val FS_READ = "fs.read"
        const val FS_WRITE = "fs.write"
        const val FS_MKDIR = "fs.mkdir"
        const val FS_COPY = "fs.copy"
        const val FS_MOVE = "fs.move"
        const val FS_DELETE = "fs.delete"
        const val FS_SEARCH = "fs.search"
        const val UI_HIERARCHY = "ui.hierarchy"
        const val UI_SCREENSHOT = "ui.screenshot"
        const val UI_TAP = "ui.tap"
        const val UI_SWIPE = "ui.swipe"
        const val UI_LONG_PRESS = "ui.longPress"
        const val UI_WAIT_FOR_TEXT = "ui.waitForText"
        const val UI_TEXT = "ui.text"
        const val UI_KEY = "ui.key"
        const val SHELL_RUN = "shell.run"

        private const val NOTIFICATION_KEY = "notificationKey"
        private const val ACTION_INDEX = "actionIndex"
        private const val LIMIT = "limit"
        private const val MAX_NOTIFICATION_ACTION_INDEX = 15
        private const val SHELL_COMMAND = "command"
        private const val TIMEOUT_MILLIS = "timeoutMillis"
        private const val DEFAULT_SHELL_TIMEOUT_MILLIS = 30_000L
        private const val MAX_SHELL_TIMEOUT_MILLIS = 300_000L
        private const val MAX_SHELL_COMMAND_BYTES = 64 * 1024
        private const val MAX_UI_COORDINATE = 32_767
        private const val MAX_UI_SWIPE_DURATION_MILLIS = 10_000
        private const val DEFAULT_UI_SWIPE_DURATION_MILLIS = 300
        private const val MIN_UI_LONG_PRESS_DURATION_MILLIS = 250
        private const val MAX_UI_LONG_PRESS_DURATION_MILLIS = 5_000
        private const val DEFAULT_UI_LONG_PRESS_DURATION_MILLIS = 700
        private const val MAX_UI_WAIT_TIMEOUT_MILLIS = 30_000L
        private const val DEFAULT_UI_WAIT_TIMEOUT_MILLIS = 10_000L
        private const val UI_WAIT_POLL_MILLIS = 250L
        private const val MAX_UI_WAIT_TEXT_BYTES = 2 * 1024
        private const val MAX_UI_TEXT_BYTES = 2 * 1024
        private const val MAX_UI_KEY_CODE = 1_000
        private const val X = "x"
        private const val Y = "y"
        private const val START_X = "startX"
        private const val START_Y = "startY"
        private const val END_X = "endX"
        private const val END_Y = "endY"
        private const val DURATION_MILLIS = "durationMillis"
        private const val TEXT = "text"
        private const val KEY_CODE = "keyCode"
        private const val COMPONENT = "component"
        private const val ACTION = "action"
        private const val DATA_URI = "dataUri"
        private const val NAMESPACE = "namespace"
        private const val KEY = "key"
        private const val VALUE = "value"
        private const val OP_NAME = "opName"
        private const val MODE = "mode"
        private const val LINES = "lines"
        private const val BUFFER = "buffer"
        private const val CASE_SENSITIVE = "caseSensitive"
        private const val PATH = "path"
        private const val SOURCE_PATH = "sourcePath"
        private const val DESTINATION_PATH = "destinationPath"
        private const val DATA_BASE64 = "dataBase64"
        private const val OFFSET = "offset"
        private const val MAX_BYTES = "maxBytes"
        private const val TRUNCATE = "truncate"
        private const val CREATE_PARENTS = "createParents"
        private const val PARENTS = "parents"
        private const val RECURSIVE = "recursive"
        private const val QUERY = "query"
        private const val MAX_DEPTH = "maxDepth"
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
        private const val TRUSTED_COMMAND_TIMEOUT_MILLIS = 30_000L
        private const val LOGCAT_TIMEOUT_MILLIS = 30_000L
        private const val DEFAULT_LOGCAT_LINES = 100
        private const val MAX_LOGCAT_LINES = 500
        private const val DEFAULT_LOGCAT_BUFFER = "main"
        private const val MAX_PROCESS_RESULTS = 1_000
        private const val MAX_COMPONENT_BYTES = 512
        private const val MAX_ACTION_BYTES = 256
        private const val MAX_DATA_URI_BYTES = 2 * 1024
        private const val MAX_SETTING_VALUE_BYTES = 4 * 1024
        private const val MAX_SETTINGS_KEY_BYTES = 128
        private const val MAX_APP_OP_NAME_BYTES = 80
        private const val MAX_TRUSTED_ERROR_CHARS = 300
        private const val MAX_TRUSTED_TEXT_RESULT_CHARS = 4_000
        private const val MAX_FS_PATH_BYTES = 512
        private const val MAX_FS_READ_BYTES = 128 * 1024
        private const val DEFAULT_FS_READ_BYTES = 64 * 1024
        private const val MAX_FS_WRITE_BYTES = 32 * 1024
        private const val MAX_FS_WRITE_BASE64_BYTES = 44 * 1024
        private const val MAX_FS_QUERY_BYTES = 256
        private const val MAX_FS_RESULTS = 100
        private const val MAX_FS_SEARCH_DEPTH = 10
        private const val DEFAULT_FS_SEARCH_DEPTH = 4
        private const val MAX_FS_OFFSET = 1L shl 40
        private val TRUSTED_MUTATION_ROOTS = setOf("/sdcard", "/storage/emulated/0", "/data/local/tmp")
        private val SETTINGS_NAMESPACES = setOf("system", "secure", "global")
        private val APP_OP_MODES = setOf("allow", "ignore", "deny", "default", "foreground")
        private val LOGCAT_BUFFERS = setOf("main", "system", "events", "crash", "radio", "all")
        private val SETTINGS_KEY_REGEX = Regex("^[A-Za-z0-9._:-]{1,128}$")
        private val APP_OP_NAME_REGEX = Regex("^[A-Za-z0-9_.:-]{1,80}$")
        private val COMPONENT_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_.]*/\\.?[A-Za-z_][A-Za-z0-9_.$]*$")
        private val ACTION_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_.]{1,255}$")
        private const val TYPED_DROID_PACKAGE = "io.github.arttvad9r.typeddroid"
        private val PACKAGE_NAME_REGEX = Regex(
            "^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$"
        )
    }
}
