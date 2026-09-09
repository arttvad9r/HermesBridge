package io.github.arttvad9r.hermesbridge.security

import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultToolPolicyTest {
    @Test
    fun readOnlyToolsDoNotPrompt() {
        val tool = BridgeTool("device_health", ToolRisk.READ_ONLY)
        assertEquals(ApprovalDecision.ALLOW, DefaultToolPolicy.decision(tool))
    }

    @Test
    fun mutatingAndPrivilegedToolsRequireApproval() {
        assertEquals(
            ApprovalDecision.REQUIRE_APPROVAL,
            DefaultToolPolicy.decision(BridgeTool("delete_file", ToolRisk.MUTATING)),
        )
        assertEquals(
            ApprovalDecision.REQUIRE_APPROVAL,
            DefaultToolPolicy.decision(BridgeTool("install_apk", ToolRisk.PRIVILEGED)),
        )
    }

    @Test
    fun dangerousGenericToolsAreDeniedEvenIfMisclassified() {
        assertEquals(
            ApprovalDecision.DENY,
            DefaultToolPolicy.decision(BridgeTool("run_shell", ToolRisk.READ_ONLY)),
        )
        assertEquals(
            ApprovalDecision.DENY,
            DefaultToolPolicy.decision(BridgeTool("arbitrary_intent", ToolRisk.READ_ONLY)),
        )
    }
}
