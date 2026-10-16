package io.github.arttvad9r.typeddroid.security

enum class ToolRisk {
    READ_ONLY,
    MUTATING,
    PRIVILEGED,
    UI_CONTROL,
    RAW_SHELL,
}

/**
 * SAFE keeps the original TypedDroid typed-capability boundary.
 * TRUSTED is an explicit opt-in for the full-control product surface.
 *
 * Device privilege (standard Android, embedded ADB, root) is intentionally
 * modeled separately from agent trust.
 */
enum class AgentTrustMode {
    SAFE,
    TRUSTED,
}

enum class ApprovalDecision {
    ALLOW,
    REQUIRE_APPROVAL,
    DENY,
}

data class BridgeTool(
    val name: String,
    val risk: ToolRisk,
)

object DefaultToolPolicy {
    private val alwaysForbiddenNames = setOf(
        "unlock_with_pin",
    )

    private val trustedOnlyNames = setOf(
        "run_shell",
        "shell.run",
        "arbitrary_intent",
        "arbitrary_content_uri",
    )

    fun decision(
        tool: BridgeTool,
        trustMode: AgentTrustMode = AgentTrustMode.SAFE,
    ): ApprovalDecision {
        if (tool.name in alwaysForbiddenNames) return ApprovalDecision.DENY
        if (tool.name in trustedOnlyNames && trustMode != AgentTrustMode.TRUSTED) {
            return ApprovalDecision.DENY
        }

        return when (tool.risk) {
            ToolRisk.READ_ONLY -> ApprovalDecision.ALLOW
            ToolRisk.RAW_SHELL -> if (trustMode == AgentTrustMode.TRUSTED) {
                ApprovalDecision.ALLOW
            } else {
                ApprovalDecision.DENY
            }
            ToolRisk.MUTATING,
            ToolRisk.PRIVILEGED,
            ToolRisk.UI_CONTROL,
            -> ApprovalDecision.REQUIRE_APPROVAL
        }
    }
}
