# Architecture

## Principles

1. **Outbound from phone.** The phone initiates the long-lived connection. No inbound port is required on Android.
2. **Typed tools, not shell.** Hermes receives purpose-built operations.
3. **Least privilege.** Modules and permissions are enabled only when needed.
4. **Fail closed.** Unknown commands, invalid schemas and unavailable privileged backends are rejected.
5. **Local policy wins.** The Android app may refuse an operation even if Hermes requested it.
6. **Reuse proven components.** `droid-mcp` is the planned MCP/tool foundation; Shizuku is the first privileged backend.

## Runtime topology

```text
+------------------+
| User / Telegram  |
+---------+--------+
          |
          v
+------------------+
| Hermes on VPS    |
+---------+--------+
          |
          v
+---------------------------+
| Hermes Bridge relay       |
| - device registry         |
| - pairing                 |
| - command routing         |
| - approval notifications  |
+------------+--------------+
             ^
             | outbound authenticated TLS/WebSocket
             |
+------------+--------------+
| Android Hermes Bridge     |
|                           |
| Connection manager        |
| Capability registry       |
| Approval policy           |
| Audit log                 |
| Tool adapters             |
+------+------+-------------+
       |      |
       |      +----------------------+
       |                             |
       v                             v
Android APIs / droid-mcp       Optional backends
                               - Usage Access
                               - Shizuku
                               - policy-gated UI-control session
```

## Android layers

### UI

Compose screens:

- status/dashboard;
- pairing;
- setup/capability wizard;
- approvals;
- audit/history;
- settings.

### Domain

Pure Kotlin where possible:

- `BridgeTool` catalog;
- risk classification;
- approval policy;
- pairing-code validation;
- relay protocol models;
- connection state.

Domain code should remain testable without Android.

### Device/tool layer

Typed adapters exposing only Hermes Bridge-approved tools.

Planned `droid-mcp` adoption is incremental. Do not depend on the monolithic/all-tools module in production. Import only required modules and map their tool surface into a Hermes Bridge allowlist.

Initial candidates:

- device;
- apps;
- files/downloads;
- network;
- usage where supported;
- Shizuku;
- audit.

Screen/UI tooling is optional and must be separately enabled.

### Privileged layer

Shizuku is the initial non-root privileged backend.

Hermes Bridge must prefer dedicated operations such as `force_stop_app` or `install_apk` over a generic shell command. Generic shell execution is not exposed to Hermes.

### UI-control boundary

The standard Hermes Bridge build does not use `AccessibilityService` as an autonomous AI-control backend. See [ADR 0003](decisions/0003-ui-control-policy.md).

Future UI-control work must prefer typed Android APIs and narrow typed Shizuku operations. If screen pixels are required, capture is an explicit short-lived MediaProjection session with fresh system consent for each new session and the required foreground-service lifecycle. Capture is never restored from boot and secure/unavailable content is not bypassed.

UI-control authority is separate from base relay connectivity and ordinary Shizuku setup. Typed tap/swipe/input operations, if implemented, require an explicit local UI-control session/approval and remain bounded and auditable.

### Transport

V1 transport is a persistent outbound connection from Android to a relay near Hermes.

Requirements:

- TLS;
- authenticated device;
- reconnect with exponential backoff + jitter;
- heartbeat;
- message IDs and idempotency;
- bounded queue;
- no command execution before authentication;
- command schema versioning.

See [PROTOCOL.md](PROTOCOL.md).

## Dependency boundary with droid-mcp

`droid-mcp` solves Android MCP/tool implementation and optional Shizuku integration. Hermes Bridge adds:

- user-friendly pairing;
- outbound relay transport;
- opinionated tool allowlist;
- local approval policy;
- capability setup UX;
- Hermes-specific lifecycle and reconnect behavior.

This avoids forking/reimplementing its Android tool internals while keeping our security policy independent.

## Current implementation

The current app includes:

- Compose pairing/setup/dashboard and local approval/history UI;
- an authenticated outbound relay connection with challenge/response device identity, reconnect and revocation flows;
- a narrow local Hermes MCP adapter and Android-side typed tool router;
- standard Android health/app/file adapters plus opt-in SAF and Usage Access capabilities;
- Shizuku-backed typed privileged operations with no agent-facing raw shell;
- exact local approval for mutating/privileged operations and a bounded local audit history;
- CI gates for tests, lint, debug/release artifacts, signing-path verification and reproducible unsigned release builds.

Physical-device network, permission and privileged-operation validation remains a separate release gate. UI automation is not implemented and must follow ADR 0003 rather than adding an Accessibility placeholder.
