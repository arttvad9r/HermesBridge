package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.security.ApprovalManager
import io.github.arttvad9r.hermesbridge.security.ApprovalTicket
import io.github.arttvad9r.hermesbridge.security.ToolRisk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject

internal data class RemoteApprovalNotification(
    val id: String,
    val tool: String,
    val risk: ToolRisk,
    val expiresAtEpochMillis: Long,
)

object BridgeApprovalRuntime {
    private val manager = ApprovalManager()
    private val mutablePendingTickets = MutableStateFlow<List<ApprovalTicket>>(emptyList())
    private val remoteNotificationLock = Any()
    private val remoteNotifications = ArrayDeque<RemoteApprovalNotification>()

    val pendingTickets: StateFlow<List<ApprovalTicket>> = mutablePendingTickets.asStateFlow()

    fun request(
        tool: String,
        risk: ToolRisk,
        arguments: JsonObject,
        displaySummary: String,
    ): ApprovalTicket {
        val ticket = manager.request(tool, risk, arguments, displaySummary)
        enqueueRemoteNotification(ticket)
        publish()
        return ticket
    }

    fun approve(id: String): ApprovalTicket? {
        val result = manager.approve(id)
        if (result != null) {
            dropRemoteNotification(result.id)
            BridgeAuditRuntime.recordApproval(result, BridgeAuditRuntime.APPROVAL_APPROVED)
        }
        publish()
        return result
    }

    fun deny(id: String): ApprovalTicket? {
        val result = manager.deny(id)
        if (result != null) {
            dropRemoteNotification(result.id)
            BridgeAuditRuntime.recordApproval(result, BridgeAuditRuntime.APPROVAL_DENIED)
        }
        publish()
        return result
    }

    fun consumeApproved(tool: String, arguments: JsonObject): ApprovalTicket? {
        val result = manager.consumeApproved(tool, arguments)
        if (result != null) dropRemoteNotification(result.id)
        publish()
        return result
    }

    internal fun takeRemoteNotification(): RemoteApprovalNotification? = synchronized(remoteNotificationLock) {
        if (remoteNotifications.isEmpty()) null else remoteNotifications.removeFirst()
    }

    fun refresh() {
        publish()
    }

    fun clear() {
        manager.clear()
        synchronized(remoteNotificationLock) { remoteNotifications.clear() }
        publish()
    }

    private fun enqueueRemoteNotification(ticket: ApprovalTicket) {
        val expectedRisk = REMOTE_NOTIFICATION_TOOLS[ticket.tool] ?: return
        if (ticket.risk != expectedRisk) return
        val notification = RemoteApprovalNotification(
            id = ticket.id,
            tool = ticket.tool,
            risk = ticket.risk,
            expiresAtEpochMillis = ticket.expiresAtEpochMillis,
        )
        synchronized(remoteNotificationLock) {
            dropRemoteNotificationLocked(ticket.id)
            if (remoteNotifications.size >= MAX_REMOTE_NOTIFICATIONS) {
                remoteNotifications.removeFirst()
            }
            remoteNotifications.addLast(notification)
        }
    }

    private fun dropRemoteNotification(id: String) {
        synchronized(remoteNotificationLock) {
            dropRemoteNotificationLocked(id)
        }
    }

    private fun dropRemoteNotificationLocked(id: String) {
        if (remoteNotifications.none { it.id == id }) return
        val retained = remoteNotifications.filterNot { it.id == id }
        remoteNotifications.clear()
        remoteNotifications.addAll(retained)
    }

    private fun publish() {
        mutablePendingTickets.value = manager.pending()
    }

    private const val MAX_REMOTE_NOTIFICATIONS = 16
    private val REMOTE_NOTIFICATION_TOOLS = mapOf(
        "files.delete" to ToolRisk.MUTATING,
        "apps.install" to ToolRisk.MUTATING,
        "apps.uninstall" to ToolRisk.MUTATING,
        "apps.forceStop" to ToolRisk.PRIVILEGED,
        "apps.revokePermission" to ToolRisk.PRIVILEGED,
    )
}
