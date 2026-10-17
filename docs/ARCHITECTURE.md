# Architecture

## Principles

1. **Agent-neutral.** Android authority is independent of Codex, Claude Code, OpenCode, Hermes, Cursor, or any other MCP host.
2. **Typed by default, explicit shell when trusted.** SAFE exposes bounded typed operations; a pairing explicitly switched to TRUSTED may additionally use the named raw-shell capability.
3. **Local MCP boundary.** Agent integration and the relay admin API run locally on the user's computer; the relay is managed internally by `typeddroid-mcp`.
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
| typeddroid-mcp                       |
| - agent-neutral schemas              |
| - runtime/relay lifecycle manager    |
+-------------------+------------------+
                    |
                    | loopback HTTP
                    v
+--------------------------------------+
| Internal local relay                 |
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
| Android TypedDroid                |
| - Keystore identity                  |
| - typed tool router                  |
| - local approval policy              |
| - bounded audit history              |
| - Android APIs / SAF / UsageStats    |
| - owned shell-UID helper backend     |
+--------------------------------------+
```

Hermes Agent is one optional adapter/configuration on top of the generic MCP boundary. It has no additional protocol authority.

## Agent boundary

The canonical public integration is `mcp_adapter/typeddroid_mcp` over MCP stdio. Agent-specific integrations may configure or package that server, but must not add tools that bypass the generic allowlist.

MCP is deliberately local because stdio is broadly supported and keeps relay credentials out of remote agent configuration. The local relay is an implementation detail started/reused by `typeddroid-mcp`; agent configuration contains only the MCP executable.

## Internal relay runtime

The Kotlin relay binds to `127.0.0.1`, persists device public keys/state under the current user, and exposes its admin API only locally. `typeddroid-mcp` owns its normal lifecycle through a locked multi-process lease registry plus detached cleanup watchdogs; users do not start a second product command.

Device-facing transport is separate from agent integration. The managed relay has two deliberately different listeners: the admin connector remains loopback-only, while an optional TLS device-ingress connector may bind to private interfaces. The direct connector exposes only `/health`, `/ws/device`, and token-gated artifact/upload data-plane routes; `/api/v1/*` is rejected on that port even with the correct local admin token.

After a device proves possession of its persisted identity key, the post-authentication `transport.hints` message may contain short-lived private literal WSS candidates plus the SHA-256 pin of the direct-ingress certificate. Android validates and persists those hints, prefers them before the configured Internet endpoint, and uses the same device challenge/response on every route. `ConnectivityManager.NetworkCallback` closes the old socket and wakes reconnect immediately when the default network changes. A future hosted rendezvous/relay can plug into the same endpoint abstraction without changing Android authority or the MCP protocol.

## Android layers

### UI

Compose screens provide pairing, setup/capability controls, local approvals, audit/history and status.

### Domain

Pure Kotlin where possible: typed tool catalog, risk classes, approval policy, protocol models, pairing validation and connection state.

### Device/tool layer

SAFE mode registers purpose-built operations only. Package visibility remains narrow and SAF stays scoped. The owned helper is a separate opt-in capability, and TRUSTED mode additionally permits the explicit raw-shell surface.

### Privileged layer

TypedDroid bootstraps its own `app_process` helper as Android shell UID through Wireless Debugging. The app and helper communicate over a loopback-only endpoint authenticated by a random 256-bit bootstrap secret. Android SELinux does not permit an ordinary app domain to connect to a shell-owned Unix stream socket. Typed operations use fixed argv; raw shell is separately available only to TRUSTED pairings.

### UI-control boundary

TRUSTED mode exposes typed screenshot/hierarchy/tap/swipe/long-press/text/key/open/wait primitives through the owned shell-UID helper. SAFE denies them. Protected Android surfaces may still hide content or reject input; TypedDroid does not claim to bypass those platform boundaries.

## Security ownership

The agent, MCP process and internal relay are treated as potentially compromised. Android remains the final authority for dangerous actions. Local one-use approvals are bound to canonical arguments/content fingerprints and cannot be resolved by an MCP token, host admin token, or notification channel.

See `SECURITY.md`, `PROTOCOL.md`, `MCP.md`, and `HOST.md`.
