package io.github.arttvad9r.hermesbridge.security

enum class ToolRisk {
    READ_ONLY,
    MUTATING,
    PRIVILEGED,
    UI_CONTROL,
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
    private val forbiddenNames = setOf(
        "run_shell",
        "arbitrary_intent",
        "arbitrary_content_uri",
        "unlock_with_pin",
    )

    fun decision(tool: BridgeTool): ApprovalDecision {
        if (tool.name in forbiddenNames) return ApprovalDecision.DENY

        return when (tool.risk) {
            ToolRisk.READ_ONLY -> ApprovalDecision.ALLOW
            ToolRisk.MUTATING,
            ToolRisk.PRIVILEGED,
            ToolRisk.UI_CONTROL,
            -> ApprovalDecision.REQUIRE_APPROVAL
        }
    }
}
