package io.github.arttvad9r.hermesbridge.relay

import io.github.arttvad9r.hermesbridge.protocol.ApprovalRequestPayload
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalNotificationStoreTest {
    @Test
    fun `record keeps target-free notification metadata only until ttl`() {
        var now = 1_000_000L
        val store = ApprovalNotificationStore { now }
        val payload = payload()

        val recorded = store.record(DEVICE_ID, payload)

        requireNotNull(recorded)
        assertEquals("apps.forceStop", recorded.tool)
        assertEquals("PRIVILEGED", recorded.risk)
        assertEquals(now + 120_000L, recorded.expiresAtEpochMillis)
        assertEquals(listOf(recorded), store.list(DEVICE_ID))

        now += 120_000L
        assertTrue(store.list(DEVICE_ID).isEmpty())
    }

    @Test
    fun `invalid ids unknown tools mismatched risks and excessive ttl fail closed`() {
        val store = ApprovalNotificationStore { 1_000_000L }

        assertNull(store.record(DEVICE_ID, payload(approvalId = "approval_bad")))
        assertNull(store.record(DEVICE_ID, payload(tool = "unknown.action")))
        assertNull(store.record(DEVICE_ID, payload(risk = "MUTATING")))
        assertNull(store.record(DEVICE_ID, payload(risk = "READ_ONLY")))
        assertNull(
            store.record(
                DEVICE_ID,
                payload(expiresInMillis = ApprovalNotificationStore.MAX_APPROVAL_TTL_MILLIS + 1),
            )
        )
        assertTrue(store.list(DEVICE_ID).isEmpty())
    }

    @Test
    fun `same approval id is upserted instead of growing the store`() {
        var now = 1_000_000L
        val store = ApprovalNotificationStore { now }
        val approvalId = "approval_${UUID.randomUUID()}"

        store.record(DEVICE_ID, payload(approvalId = approvalId, expiresInMillis = 60_000L))
        now += 1_000L
        store.record(DEVICE_ID, payload(approvalId = approvalId, expiresInMillis = 120_000L))

        val pending = store.list(DEVICE_ID)
        assertEquals(1, pending.size)
        assertEquals(now + 120_000L, pending.single().expiresAtEpochMillis)
    }

    @Test
    fun `store evicts oldest notifications beyond global capacity`() {
        var now = 1_000_000L
        val store = ApprovalNotificationStore { now }

        repeat(ApprovalNotificationStore.MAX_APPROVAL_NOTIFICATIONS + 1) { index ->
            store.record(
                DEVICE_ID,
                payload(approvalId = "approval_${UUID(0L, index.toLong())}"),
            )
            now += 1L
        }

        val notifications = store.list(DEVICE_ID)
        assertEquals(ApprovalNotificationStore.MAX_APPROVAL_NOTIFICATIONS, notifications.size)
        assertTrue(notifications.none { it.approvalId == "approval_${UUID(0L, 0L)}" })
    }

    private fun payload(
        approvalId: String = "approval_${UUID.randomUUID()}",
        tool: String = "apps.forceStop",
        risk: String = "PRIVILEGED",
        expiresInMillis: Long = 120_000L,
    ) = ApprovalRequestPayload(
        approvalId = approvalId,
        tool = tool,
        risk = risk,
        expiresInMillis = expiresInMillis,
    )

    private companion object {
        const val DEVICE_ID = "device_test-123"
    }
}
