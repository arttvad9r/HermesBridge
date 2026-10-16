# Architecture

## Principles

1. **Agent-neutral.** Android authority is independent of Codex, Claude Code, OpenCode, Hermes, Cursor, or any other MCP host.
2. **Typed tools, not shell.** The agent sees fixed operations, never arbitrary argv or a generic Android command selector.
3. **Local host boundary.** Agent integration and the admin API run locally on the user's computer.
4. **Outbound from Android.** The phone initiates the authenticated device connection; no management port is opened on Android.
5. **Android policy wins.** A compromised agent or host still cannot bypass Android-side validation and local approval.
6. **Least privilege and bounded output.** Optional capabilities remain opt-in and results have explicit size/count limits.
7. **Fail closed.** Unknown tools, malformed schemas, expired approvals and unavailable privileged backends are rejected.

## Runtime topology

```text
+--------------------------------------+
| Any MCP-capable AI agent             |
| Codex / Claude / OpenCode / Hermes…  |
+-------------------+------------------+
                    |
                    | MCP stdio
                    v
+--------------------------------------+
| Generic MCP adapter                  |
| - agent-neutral schemas              |
| - second typed allowlist             |
+-------------------+------------------+
                    |
                    | loopback HTTP
                    v
+--------------------------------------+
| Local Bridge Host                    |
| - pairing/device registry            |
| - command routing                    |
| - idempotency                        |
| - short-lived APK artifacts          |
| - redacted approval notifications    |
+-------------------+------------------+
                    ^
                    | authenticated Android channel
                    |
+-------------------+------------------+
| Android Hermes Bridge                |
| - Keystore identity                  |
| - typed tool router                  |
| - local approval policy              |
| - bounded audit history              |
| - Android APIs / SAF / UsageStats    |
| - optional typed Shizuku backend     |
+--------------------------------------+
```

Hermes Agent is one optional adapter/configuration on top of the generic MCP boundary. It has no additional protocol authority.

## Agent boundary

The canonical public integration is `mcp_adapter/android_bridge_mcp` over MCP stdio. Agent-specific integrations may configure or package that server, but must not add tools that bypass the generic allowlist.

MCP is deliberately local because stdio is broadly supported and keeps Bridge Host credentials off remote agent configuration. Streamable HTTP may be added later for clients that genuinely require a remote MCP endpoint, but it is not required by the core design.

## Bridge Host

The existing Kotlin relay runtime is the first Bridge Host transport implementation. It binds to `127.0.0.1`, persists device public keys/state under the current user, and exposes its admin API only locally.

Device-facing WSS ingress is separate from agent integration. No specific cloud provider, tunnel or reverse proxy is part of the architecture.

## Android layers

### UI

Compose screens provide pairing, setup/capability controls, local approvals, audit/history and status.

### Domain

Pure Kotlin where possible: typed tool catalog, risk classes, approval policy, protocol models, pairing validation and connection state.

### Device/tool layer

Only purpose-built operations are registered. Package visibility remains narrow; file access is restricted to the user-selected SAF tree; Usage Access and Shizuku are separate opt-in capabilities.

### Privileged layer

Shizuku provides selected non-root privileged operations through a typed Binder/UserService boundary. No generic shell method crosses that boundary or the agent boundary.

### UI-control boundary

The standard build does not expose autonomous Accessibility-based control. Any future UI-control feature remains a distinct, explicit, short-lived authority described by ADR 0003.

## Security ownership

The agent and Bridge Host are treated as potentially compromised. Android remains the final authority for dangerous actions. Local one-use approvals are bound to canonical arguments/content fingerprints and cannot be resolved by an MCP token, host admin token, or notification channel.

See `SECURITY.md`, `PROTOCOL.md`, `MCP.md`, and `HOST.md`.
