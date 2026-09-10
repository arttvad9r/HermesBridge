package io.github.arttvad9r.hermesbridge.security

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalManagerTest {
    @Test
    fun fingerprintIgnoresObjectKeyOrderButPreservesArrayOrder() {
        val first = buildJsonObject {
            put("package", "com.example.app")
            put(
                "flags",
                buildJsonObject {
                    put("keepData", false)
                    put("user", 0)
                },
            )
        }
        val reordered = JsonObject(
            linkedMapOf(
                "flags" to buildJsonObject {
                    put("user", 0)
                    put("keepData", false)
                },
                "package" to JsonPrimitive("com.example.app"),
            )
        )

        assertEquals(
            ApprovalFingerprint.forRequest("apps.uninstall", first),
            ApprovalFingerprint.forRequest("apps.uninstall", reordered),
        )

        val arrayA = buildJsonObject {
            put(
                "items",
                buildJsonArray {
                    add(JsonPrimitive("a"))
                    add(JsonPrimitive("b"))
                },
            )
        }
        val arrayB = buildJsonObject {
            put(
                "items",
                buildJsonArray {
                    add(JsonPrimitive("b"))
                    add(JsonPrimitive("a"))
                },
            )
        }
        assertNotEquals(
            ApprovalFingerprint.forRequest("files.delete", arrayA),
            ApprovalFingerprint.forRequest("files.delete", arrayB),
        )
    }

    @Test
    fun approvalIsOneTimeAndBoundToExactArguments() {
        var now = 1_000_000L
        val manager = ApprovalManager(nowMillis = { now }, ttlMillis = 60_000L)
        val requested = buildJsonObject { put("packageName", "com.example.app") }
        val different = buildJsonObject { put("packageName", "com.other.app") }

        val ticket = manager.request(
            tool = "apps.uninstall",
            risk = ToolRisk.MUTATING,
            arguments = requested,
            displaySummary = "Удалить Example",
        )
        assertEquals(ApprovalStatus.PENDING, ticket.status)
        assertEquals(ApprovalStatus.APPROVED, manager.approve(ticket.id)?.status)

        assertNull(manager.consumeApproved("apps.uninstall", different))
        val consumed = manager.consumeApproved("apps.uninstall", requested)
        assertEquals(ticket.id, consumed?.id)
        assertEquals(ApprovalStatus.CONSUMED, consumed?.status)
        assertNull(manager.consumeApproved("apps.uninstall", requested))
    }

    @Test
    fun duplicatePendingRequestReusesTicket() {
        val manager = ApprovalManager(ttlMillis = 60_000L)
        val arguments = buildJsonObject { put("packageName", "com.example.app") }

        val first = manager.request(
            "apps.uninstall",
            ToolRisk.MUTATING,
            arguments,
            "Удалить Example",
        )
        val second = manager.request(
            "apps.uninstall",
            ToolRisk.MUTATING,
            arguments,
            "Удалить Example",
        )

        assertSame(first, second)
        assertEquals(1, manager.pending().size)
    }

    @Test
    fun approvalExpiresAndCannotBeConsumed() {
        var now = 5_000L
        val manager = ApprovalManager(nowMillis = { now }, ttlMillis = 10_000L)
        val arguments = buildJsonObject { put("packageName", "com.example.app") }
        val ticket = manager.request(
            "apps.uninstall",
            ToolRisk.MUTATING,
            arguments,
            "Удалить Example",
        )
        manager.approve(ticket.id)

        now += 10_001L

        assertEquals(ApprovalStatus.EXPIRED, manager.find(ticket.id)?.status)
        assertNull(manager.consumeApproved("apps.uninstall", arguments))
    }

    @Test
    fun approvalDisplaySummaryNormalizesControlsAndKeepsFingerprintExact() {
        val exactName = "line\nname\u202E\u2028.txt"
        val manager = ApprovalManager(ttlMillis = 60_000L)
        val arguments = buildJsonObject {
            put("pathSegments", buildJsonArray { add(JsonPrimitive(exactName)) })
        }
        val expectedFingerprint = ApprovalFingerprint.forRequest("files.delete", arguments)

        val ticket = manager.request(
            tool = "files.delete",
            risk = ToolRisk.MUTATING,
            arguments = arguments,
            displaySummary = "  Удалить Documents/$exactName\t" + "x".repeat(300),
        )

        assertEquals(expectedFingerprint, ticket.argumentsFingerprint)
        assertEquals(ApprovalManager.MAX_SUMMARY_LENGTH, ticket.displaySummary.length)
        assertFalse(ticket.displaySummary.any(Char::isISOControl))
        assertFalse(ticket.displaySummary.contains('\u202E'))
        assertFalse(ticket.displaySummary.contains('\u2028'))
        assertTrue(ticket.displaySummary.startsWith("Удалить Documents/line name  .txt x"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun readOnlyToolCannotCreateApprovalTicket() {
        ApprovalManager().request(
            tool = "device.health",
            risk = ToolRisk.READ_ONLY,
            arguments = buildJsonObject {},
            displaySummary = "Health",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun forbiddenToolCannotCreateApprovalTicket() {
        ApprovalManager().request(
            tool = "run_shell",
            risk = ToolRisk.PRIVILEGED,
            arguments = buildJsonObject { put("command", "id") },
            displaySummary = "Shell",
        )
    }
}
