package io.github.arttvad9r.hermesbridge

import io.github.arttvad9r.hermesbridge.security.ToolRisk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PhysicalMutationApprovalContractTest {
    @After
    fun tearDown() {
        BridgeApprovalRuntime.clear()
    }

    @Test
    fun changedSemanticArgumentsCannotConsumeApprovedPhysicalMutation() {
        cases().forEach { case ->
            BridgeApprovalRuntime.clear()
            val ticket = BridgeApprovalRuntime.request(
                tool = case.tool,
                risk = case.risk,
                arguments = case.original,
                displaySummary = "Physical mutation contract",
            )
            BridgeApprovalRuntime.approve(ticket.id)

            assertNull(
                "${case.tool} reused approval after semantic argument change",
                BridgeApprovalRuntime.consumeApproved(case.tool, case.changed),
            )
            assertNotNull(
                "${case.tool} lost its approval for the exact normalized arguments",
                BridgeApprovalRuntime.consumeApproved(case.tool, case.original),
            )
            assertNull(
                "${case.tool} approval was not single-use",
                BridgeApprovalRuntime.consumeApproved(case.tool, case.original),
            )
        }
    }

    private fun cases() = listOf(
        Case(
            tool = "files.delete",
            risk = ToolRisk.MUTATING,
            original = buildJsonObject {
                put("pathSegments", buildJsonArray { add("fixture-a.txt") })
                put("directory", false)
                put("sizeBytes", 10L)
                put("lastModifiedEpochMillis", 100L)
            },
            changed = buildJsonObject {
                put("pathSegments", buildJsonArray { add("fixture-b.txt") })
                put("directory", false)
                put("sizeBytes", 10L)
                put("lastModifiedEpochMillis", 100L)
            },
        ),
        Case(
            tool = "apps.forceStop",
            risk = ToolRisk.PRIVILEGED,
            original = buildJsonObject { put("packageName", "io.example.fixture") },
            changed = buildJsonObject { put("packageName", "io.example.other") },
        ),
        Case(
            tool = "apps.revokePermission",
            risk = ToolRisk.PRIVILEGED,
            original = buildJsonObject {
                put("packageName", "io.example.fixture")
                put("permissionName", "android.permission.CAMERA")
                put("userId", 0)
            },
            changed = buildJsonObject {
                put("packageName", "io.example.fixture")
                put("permissionName", "android.permission.RECORD_AUDIO")
                put("userId", 0)
            },
        ),
        Case(
            tool = "apps.uninstall",
            risk = ToolRisk.MUTATING,
            original = buildJsonObject {
                put("packageName", "io.example.fixture")
                put("keepData", false)
            },
            changed = buildJsonObject {
                put("packageName", "io.example.fixture")
                put("keepData", true)
            },
        ),
        Case(
            tool = "apps.install",
            risk = ToolRisk.MUTATING,
            original = buildJsonObject {
                put("packageName", "io.example.fixture")
                put("versionCode", 1L)
                put("sha256", "a".repeat(64))
                put("replace", true)
            },
            changed = buildJsonObject {
                put("packageName", "io.example.fixture")
                put("versionCode", 1L)
                put("sha256", "a".repeat(64))
                put("replace", false)
            },
        ),
    )

    private data class Case(
        val tool: String,
        val risk: ToolRisk,
        val original: JsonObject,
        val changed: JsonObject,
    )
}
