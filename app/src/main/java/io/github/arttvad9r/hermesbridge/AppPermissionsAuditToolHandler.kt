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

class AppPermissionsAuditToolHandler(
    private val appsRepository: InstalledAppsRepository,
    private val permissionsRepository: AppPermissionsRepository?,
) {
    fun execute(request: CommandRequestPayload): CommandResultPayload {
        if (request.arguments.isNotEmpty()) {
            return failure(
                request.requestId,
                "invalid_arguments",
                "apps.permissionsAudit does not accept arguments.",
            )
        }
        if (
            DefaultToolPolicy.decision(BridgeTool(TOOL_NAME, ToolRisk.READ_ONLY)) !=
            ApprovalDecision.ALLOW
        ) {
            return failure(request.requestId, "policy_denied", "Local policy did not allow the tool.")
        }

        val repository = permissionsRepository
            ?: return failure(
                request.requestId,
                "app_permissions_unavailable",
                "App permission metadata repository is not configured.",
            )
        val visibleApps = appsRepository.listLaunchableApps()
        val scanApps = visibleApps.take(MAX_SCANNED_APPS)
        var skippedApps = 0
        var dangerousGrantedPermissionCount = 0
        var resultTruncated = false
        val findings = ArrayList<AppAuditFinding>()

        scanApps.forEach { app ->
            val snapshot = try {
                repository.read(app.packageName)
            } catch (_: Throwable) {
                skippedApps += 1
                return@forEach
            }
            if (snapshot.packageName != app.packageName) {
                skippedApps += 1
                return@forEach
            }

            val dangerousGranted = snapshot.permissions
                .asSequence()
                .filter { it.dangerous && it.granted }
                .take(MAX_PERMISSIONS_PER_APP + 1)
                .toList()
            if (dangerousGranted.isEmpty()) return@forEach

            dangerousGrantedPermissionCount += dangerousGranted.size
            val permissions = if (dangerousGranted.size > MAX_PERMISSIONS_PER_APP) {
                resultTruncated = true
                dangerousGranted.take(MAX_PERMISSIONS_PER_APP)
            } else {
                dangerousGranted
            }

            if (findings.size < MAX_FINDING_APPS) {
                findings += AppAuditFinding(app, permissions)
            } else {
                resultTruncated = true
            }
        }

        val scanTruncated = visibleApps.size > scanApps.size
        return CommandResultPayload(
            requestId = request.requestId,
            ok = true,
            result = buildJsonObject {
                put("visibleAppCount", visibleApps.size)
                put("scannedAppCount", scanApps.size)
                put("skippedAppCount", skippedApps)
                put("matchedAppCount", findings.size)
                put("dangerousGrantedPermissionCount", dangerousGrantedPermissionCount)
                put("scanTruncated", scanTruncated)
                put("resultTruncated", resultTruncated)
                put(
                    "apps",
                    buildJsonArray {
                        findings.forEach { finding ->
                            val app = finding.app
                            add(
                                buildJsonObject {
                                    put("packageName", app.packageName)
                                    put("label", app.label)
                                    if (app.versionName == null) put("versionName", JsonNull)
                                    else put("versionName", app.versionName)
                                    put("versionCode", app.versionCode)
                                    put("systemApp", app.systemApp)
                                    put("enabled", app.enabled)
                                    put("dangerousGrantedCount", finding.permissions.size)
                                    put(
                                        "permissions",
                                        buildJsonArray {
                                            finding.permissions.forEach { permission ->
                                                add(
                                                    buildJsonObject {
                                                        put("name", permission.name)
                                                        if (permission.group == null) put("group", JsonNull)
                                                        else put("group", permission.group)
                                                        put("implicit", permission.implicit)
                                                        put("neverForLocation", permission.neverForLocation)
                                                    }
                                                )
                                            }
                                        },
                                    )
                                }
                            )
                        }
                    },
                )
            },
        )
    }

    private data class AppAuditFinding(
        val app: InstalledAppSnapshot,
        val permissions: List<AppPermissionSnapshot>,
    )

    private fun failure(requestId: String, code: String, message: String) =
        CommandResultPayload(
            requestId = requestId,
            ok = false,
            error = ProtocolError(code, message),
        )

    companion object {
        const val TOOL_NAME = "apps.permissionsAudit"
        const val MAX_SCANNED_APPS = 200
        const val MAX_FINDING_APPS = 100
        const val MAX_PERMISSIONS_PER_APP = 30
    }
}
