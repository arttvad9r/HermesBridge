package io.github.arttvad9r.hermesbridge.relay

import io.github.arttvad9r.hermesbridge.protocol.ApprovalRequestPayload
import kotlinx.serialization.Serializable

@Serializable
data class RelayApprovalNotificationResponse(
    val deviceId: String,
    val approvalId: String,
    val tool: String,
    val risk: String,
    val expiresAtEpochMillis: Long,
)

internal class ApprovalNotificationStore(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private data class StoredApprovalNotification(
        val response: RelayApprovalNotificationResponse,
        val receivedAtEpochMillis: Long,
    )

    private val notifications = LinkedHashMap<String, StoredApprovalNotification>()

    @Synchronized
    fun record(deviceId: String, payload: ApprovalRequestPayload): RelayApprovalNotificationResponse? {
        val now = nowMillis()
        val normalized = normalizeApprovalNotification(deviceId, payload, now) ?: return null
        val key = key(normalized.deviceId, normalized.approvalId)
        notifications[key] = StoredApprovalNotification(normalized, now)
        pruneLocked()
        return normalized
    }

    @Synchronized
    fun list(deviceId: String): List<RelayApprovalNotificationResponse> {
        pruneLocked()
        return notifications.values
            .asSequence()
            .map(StoredApprovalNotification::response)
            .filter { it.deviceId == deviceId }
            .sortedBy { it.expiresAtEpochMillis }
            .toList()
    }

    @Synchronized
    fun clearDevice(deviceId: String) {
        notifications.entries.removeIf { it.value.response.deviceId == deviceId }
    }

    private fun pruneLocked() {
        val now = nowMillis()
        notifications.entries.removeIf { it.value.response.expiresAtEpochMillis <= now }
        while (notifications.size > MAX_APPROVAL_NOTIFICATIONS) {
            val oldest = notifications.entries.minByOrNull { it.value.receivedAtEpochMillis } ?: return
            notifications.remove(oldest.key)
        }
    }

    private fun key(deviceId: String, approvalId: String): String = "$deviceId\n$approvalId"

    companion object {
        const val MAX_APPROVAL_TTL_MILLIS = 10 * 60 * 1000L
        const val MAX_APPROVAL_NOTIFICATIONS = 200
        private val DEVICE_ID_REGEX = Regex("^device_[A-Za-z0-9-]{1,80}$")
        private val APPROVAL_ID_REGEX = Regex("^approval_[0-9a-fA-F-]{36}$")
        private val EXPECTED_TOOL_RISKS = mapOf(
            "files.delete" to "MUTATING",
            "apps.install" to "MUTATING",
            "apps.uninstall" to "MUTATING",
            "apps.forceStop" to "PRIVILEGED",
            "apps.revokePermission" to "PRIVILEGED",
        )

        internal fun normalizeApprovalNotification(
            deviceId: String,
            payload: ApprovalRequestPayload,
            nowEpochMillis: Long,
        ): RelayApprovalNotificationResponse? {
            if (!DEVICE_ID_REGEX.matches(deviceId)) return null
            if (!APPROVAL_ID_REGEX.matches(payload.approvalId)) return null
            if (EXPECTED_TOOL_RISKS[payload.tool] != payload.risk) return null
            if (payload.expiresInMillis !in 1..MAX_APPROVAL_TTL_MILLIS) return null

            return RelayApprovalNotificationResponse(
                deviceId = deviceId,
                approvalId = payload.approvalId,
                tool = payload.tool,
                risk = payload.risk,
                expiresAtEpochMillis = nowEpochMillis + payload.expiresInMillis,
            )
        }
    }
}
