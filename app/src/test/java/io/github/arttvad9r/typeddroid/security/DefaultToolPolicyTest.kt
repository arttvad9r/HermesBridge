package io.github.arttvad9r.typeddroid.security

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
    fun genericToolsStayDeniedInSafeMode() {
        assertEquals(
            ApprovalDecision.DENY,
            DefaultToolPolicy.decision(BridgeTool("run_shell", ToolRisk.RAW_SHELL)),
        )
        assertEquals(
            ApprovalDecision.DENY,
            DefaultToolPolicy.decision(BridgeTool("arbitrary_intent", ToolRisk.PRIVILEGED)),
        )
    }

    @Test
    fun trustedModeCanRequestFullControlTools() {
        assertEquals(
            ApprovalDecision.ALLOW,
            DefaultToolPolicy.decision(
                BridgeTool("run_shell", ToolRisk.RAW_SHELL),
                AgentTrustMode.TRUSTED,
            ),
        )
        assertEquals(
            ApprovalDecision.ALLOW,
            DefaultToolPolicy.decision(
                BridgeTool("shell.run", ToolRisk.RAW_SHELL),
                AgentTrustMode.TRUSTED,
            ),
        )
        assertEquals(
            ApprovalDecision.REQUIRE_APPROVAL,
            DefaultToolPolicy.decision(
                BridgeTool("arbitrary_intent", ToolRisk.PRIVILEGED),
                AgentTrustMode.TRUSTED,
            ),
        )
    }

    @Test
    fun pinUnlockRemainsForbiddenEvenForTrustedAgents() {
        assertEquals(
            ApprovalDecision.DENY,
            DefaultToolPolicy.decision(
                BridgeTool("unlock_with_pin", ToolRisk.PRIVILEGED),
                AgentTrustMode.TRUSTED,
            ),
        )
    }
    @Test
    fun uiControlRequiresTrustedMode() {
        assertEquals(
            ApprovalDecision.DENY,
            DefaultToolPolicy.decision(BridgeTool("ui.tap", ToolRisk.UI_CONTROL), AgentTrustMode.SAFE),
        )
        assertEquals(
            ApprovalDecision.ALLOW,
            DefaultToolPolicy.decision(BridgeTool("ui.tap", ToolRisk.UI_CONTROL), AgentTrustMode.TRUSTED),
        )
    }

}
