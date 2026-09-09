# Hermes Bridge

Android companion for giving a Hermes agent controlled, remote access to a phone.

The intended UX is deliberately simple:

1. Install one APK.
2. Pair it with Hermes using a one-time code.
3. Grant only the capabilities you want.
4. Close the app and give tasks to Hermes normally.

Hermes Bridge is **not** intended to expose a general-purpose remote shell. The agent gets a small typed tool surface, read-only by default, with exact-argument approvals for mutating/privileged actions.

> Status: functional development prototype. Authenticated outbound relay pairing, automatic reconnect, foreground service, Hermes MCP adapter, read-only diagnostics/files, local approvals and the first Shizuku-backed privileged app operations are implemented. A physical phone/VPS end-to-end privileged-action test is still required.

## Current capabilities

- P-256 device identity stored in Android Keystore.
- One-time pairing and challenge/response reconnect authentication.
- Persistent outbound WSS connection from Android to the relay.
- Foreground service with reboot/package-update recovery.
- Relay deployment examples for systemd + TLS reverse proxy.
- Local stdio MCP adapter for Hermes.
- `device_health` — battery, memory and storage totals.
- `list_apps` — launcher-visible apps without `QUERY_ALL_PACKAGES`.
- `list_files` — directory browsing only inside a folder explicitly selected through Android Storage Access Framework.
- Explicit local revoke/change control for the SAF folder grant.
- Shizuku detection/authorization status in the Android app.
- One-use, expiring approvals bound to canonical tool arguments.
- `uninstall_app` — Shizuku-backed package uninstall after local approval.
- `force_stop_app` — Shizuku-backed force-stop after local approval.
- Hermes Bridge protects its own package from uninstall/force-stop.

## Goals

- Work when the phone and laptop are on different networks.
- No laptop required after setup.
- Phone initiates an outbound encrypted connection to the Hermes-side relay.
- No routine ADB, IP, port, Termux or raw shell workflow.
- Read-only diagnostics without prompts.
- Mutating and privileged actions require explicit policy/approval.
- Shizuku is the optional privileged backend; root is not required.
- Build on existing Android/MCP components where they fit instead of reimplementing everything.

## Architecture

```text
Telegram / user
      |
      v
Hermes on VPS
      |
      +-- local stdio MCP adapter
      |          |
      |          v
      +----> Hermes Bridge relay
                   ^
                   | outbound WSS
                   |
             Android app
                   |
                   +-- Android APIs
                   +-- PackageManager (limited visibility)
                   +-- Storage Access Framework (user-selected tree)
                   +-- approval policy
                   +-- Shizuku (optional privileged backend)
                   +-- Accessibility (planned, optional)
```

The relay admin API is intended to remain on localhost beside Hermes. Only the device WebSocket and health endpoint need to be exposed through the TLS reverse proxy.

See:

- [Product specification](docs/PRODUCT_SPEC.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Security model](docs/SECURITY.md)
- [Relay protocol](docs/PROTOCOL.md)
- [Permissions and setup](docs/PERMISSIONS.md)
- [Relay deployment](docs/DEPLOYMENT.md)
- [Hermes MCP integration](docs/HERMES_MCP.md)
- [Implementation roadmap](TODO.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)

## Security baseline

The project keeps these non-negotiable rules:

- no raw `run_shell` exposed to the agent;
- no generic command passthrough in the Hermes MCP surface;
- no arbitrary Android intents or arbitrary content URIs;
- no PIN/password capture or unlock automation;
- no root requirement;
- default-deny tool registration;
- read-only tools are narrowly typed and argument-validated;
- privileged/mutating commands require one-use exact-argument approval;
- long-term device credentials are stored in Android Keystore;
- production device transport requires WSS;
- the Hermes-side admin token stays local to the VPS process boundary where possible;
- SAF file access is limited to a directory explicitly chosen by the user;
- privileged package operations validate package names and protect Hermes Bridge itself.

More detail: [docs/SECURITY.md](docs/SECURITY.md).

## Technology baseline

- Kotlin 2.1.20
- Android Gradle Plugin 8.9.1
- Gradle 8.12
- compile/target SDK 35
- min SDK 30
- Jetpack Compose + Material 3
- Java/Kotlin target 17
- Ktor WebSockets
- kotlinx.serialization
- AndroidX DocumentFile 1.1.0 for SAF tree traversal
- Shizuku API/provider 13.1.5
- Python MCP SDK v2 adapter for Hermes

The narrow privileged process logic is adapted from Apache-2.0 `droid-mcp` source pinned in `THIRD_PARTY_NOTICES.md`; no JitPack runtime/build dependency is used.

## Build

```bash
gradle :app:assembleDebug
```

The CI currently installs Gradle 8.12 explicitly because the repository does not yet contain a validated Gradle Wrapper binary.

CI runs:

- protocol/relay/app unit tests;
- Hermes MCP adapter tests;
- Android lint;
- debug APK build;
- relay distribution build;
- artifact upload.

## Repository policy

No project-wide license has been selected yet. Third-party adapted code retains its upstream attribution/license notice. Until a project license is added, normal copyright rules apply to Hermes Bridge's own code.
