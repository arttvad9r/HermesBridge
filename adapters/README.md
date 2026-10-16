# Agent adapters

Hermes Bridge does not embed or depend on a specific AI agent.

The canonical agent boundary is the generic local MCP server in `mcp_adapter/`. Any MCP host that supports local stdio servers can use the same typed Android tools.

Agent-specific directories contain configuration/help only. They must not gain extra Android authority or bypass the generic MCP allowlist.

Current adapters:

- `hermes/` — Hermes Agent configuration over the generic MCP server.

Codex, Claude Code, OpenCode, Cursor and other MCP hosts use `android-bridge-mcp` directly; see `docs/MCP.md`.
