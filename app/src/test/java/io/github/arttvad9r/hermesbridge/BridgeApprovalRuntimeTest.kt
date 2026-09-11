package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.security.ToolRisk
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class BridgeApprovalRuntimeTest {
    @After
    fun tearDown() {
        BridgeApprovalRuntime.clear()
        BridgeAuditRuntime.clear()
    }

    @Test
    fun `approval request queues target-free metadata while local ticket keeps exact summary`() {
        BridgeApprovalRuntime.clear()
        val secretTarget = "/Documents/private/secret.txt"
        val arguments = buildJsonObject { put("path", secretTarget) }

        val ticket = BridgeApprovalRuntime.request(
            tool = "files.delete",
            risk = ToolRisk.MUTATING,
            arguments = arguments,
            displaySummary = "Удалить файл $secretTarget",
        )
        val notification = BridgeApprovalRuntime.takeRemoteNotification()

        requireNotNull(notification)
        assertEquals(ticket.id, notification.id)
        assertEquals(ticket.tool, notification.tool)
        assertEquals(ticket.risk, notification.risk)
        assertEquals(ticket.expiresAtEpochMillis, notification.expiresAtEpochMillis)
        assertEquals("Удалить файл $secretTarget", ticket.displaySummary)
        assertNull(BridgeApprovalRuntime.takeRemoteNotification())
        assertEquals(listOf(ticket), BridgeApprovalRuntime.pendingTickets.value)
    }

    @Test
    fun `unknown or mismatched approval tools stay local and are never queued remotely`() {
        BridgeApprovalRuntime.request(
            tool = "unknown.action",
            risk = ToolRisk.MUTATING,
            arguments = buildJsonObject { put("secret", "do-not-route") },
            displaySummary = "Do not route this",
        )
        BridgeApprovalRuntime.request(
            tool = "apps.forceStop",
            risk = ToolRisk.MUTATING,
            arguments = buildJsonObject { put("packageName", "com.example.app") },
            displaySummary = "Mismatched risk",
        )

        assertNull(BridgeApprovalRuntime.takeRemoteNotification())
        assertEquals(2, BridgeApprovalRuntime.pendingTickets.value.size)
    }

    @Test
    fun `reused pending ticket does not duplicate remote notification`() {
        val arguments = buildJsonObject { put("packageName", "com.example.app") }
        val first = BridgeApprovalRuntime.request(
            tool = "apps.forceStop",
            risk = ToolRisk.PRIVILEGED,
            arguments = arguments,
            displaySummary = "Force-stop Example App",
        )
        val second = BridgeApprovalRuntime.request(
            tool = "apps.forceStop",
            risk = ToolRisk.PRIVILEGED,
            arguments = arguments,
            displaySummary = "Force-stop Example App",
        )

        assertEquals(first.id, second.id)
        assertEquals(first.id, BridgeApprovalRuntime.takeRemoteNotification()?.id)
        assertNull(BridgeApprovalRuntime.takeRemoteNotification())
    }

    @Test
    fun `locally resolved ticket is removed from unsent notification queue`() {
        val approved = BridgeApprovalRuntime.request(
            tool = "apps.forceStop",
            risk = ToolRisk.PRIVILEGED,
            arguments = buildJsonObject { put("packageName", "com.example.approve") },
            displaySummary = "Approve target",
        )
        BridgeApprovalRuntime.approve(approved.id)

        val denied = BridgeApprovalRuntime.request(
            tool = "apps.forceStop",
            risk = ToolRisk.PRIVILEGED,
            arguments = buildJsonObject { put("packageName", "com.example.deny") },
            displaySummary = "Deny target",
        )
        BridgeApprovalRuntime.deny(denied.id)

        assertNull(BridgeApprovalRuntime.takeRemoteNotification())
    }

    @Test
    fun `repeated local decision is rejected and not audited twice`() {
        BridgeAuditRuntime.clear()
        val ticket = BridgeApprovalRuntime.request(
            tool = "apps.forceStop",
            risk = ToolRisk.PRIVILEGED,
            arguments = buildJsonObject { put("packageName", "com.example.once") },
            displaySummary = "Force-stop once",
        )

        assertEquals(ticket.id, BridgeApprovalRuntime.approve(ticket.id)?.id)
        assertNull(BridgeApprovalRuntime.approve(ticket.id))
        assertNull(BridgeApprovalRuntime.deny(ticket.id))

        val decisionEntries = BridgeAuditRuntime.entries.value.filter {
            it.type == AuditEventType.APPROVAL && it.tool == "apps.forceStop"
        }
        assertEquals(1, decisionEntries.size)
        assertEquals(BridgeAuditRuntime.APPROVAL_APPROVED, decisionEntries.single().outcome)
    }

    @Test
    fun `remote notification queue evicts oldest entry without removing local tickets`() {
        val tickets = (0..16).map { index ->
            BridgeApprovalRuntime.request(
                tool = "apps.forceStop",
                risk = ToolRisk.PRIVILEGED,
                arguments = buildJsonObject { put("packageName", "com.example.app$index") },
                displaySummary = "Force-stop app $index",
            )
        }

        val notifications = buildList {
            while (true) {
                val notification = BridgeApprovalRuntime.takeRemoteNotification() ?: break
                add(notification)
            }
        }

        assertEquals(16, notifications.size)
        assertFalse(notifications.any { it.id == tickets.first().id })
        assertEquals(tickets.drop(1).map { it.id }, notifications.map { it.id })
        assertEquals(17, BridgeApprovalRuntime.pendingTickets.value.size)
    }

    @Test
    fun `clearing local approval state also drops unsent notifications`() {
        BridgeApprovalRuntime.request(
            tool = "apps.forceStop",
            risk = ToolRisk.PRIVILEGED,
            arguments = buildJsonObject { put("packageName", "com.example.app") },
            displaySummary = "Force-stop Example App",
        )

        BridgeApprovalRuntime.clear()

        assertNull(BridgeApprovalRuntime.takeRemoteNotification())
        assertEquals(emptyList<Any>(), BridgeApprovalRuntime.pendingTickets.value)
    }
}
