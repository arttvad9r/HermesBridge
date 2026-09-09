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
| - approval routing        |
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
                               - Accessibility
                               - Shizuku
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

The bootstrap app currently includes:

- Compose status UI;
- real local battery/memory/storage snapshot using Android APIs;
- pairing-code domain validation;
- a fail-closed transport stub;
- initial tool-risk and approval policy;
- unit tests for pairing/policy.

The transport stub intentionally refuses pairing until the relay protocol is implemented; the UI must not present a false connected state.
