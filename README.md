# Hermes Bridge

Android companion for giving a Hermes agent controlled, remote access to a phone.

The intended UX is deliberately simple:

1. Install one APK.
2. Pair it with Hermes using a one-time code.
3. Grant only the capabilities you want.
4. Close the app and give tasks to Hermes normally.

Hermes Bridge is **not** intended to expose a general-purpose remote shell. The agent gets a small typed tool surface, read-only by default, with explicit approvals planned for destructive actions.

> Status: functional development prototype. Authenticated outbound relay pairing, automatic reconnect, foreground service, Hermes MCP adapter, device health, launcher-visible app listing, and scoped read-only file browsing are implemented. Privileged/mutating actions are intentionally not enabled yet.

## Current capabilities

- P-256 device identity stored in Android Keystore.
- One-time pairing and challenge/response reconnect authentication.
- Persistent outbound WSS connection from Android to the relay.
- Foreground service with reboot/package-update recovery.
- Relay deployment examples for systemd + TLS reverse proxy.
- Local stdio MCP adapter for Hermes.
- `device_health` — battery, memory and storage totals.
- `list_apps` — launcher-visible apps without `QUERY_ALL_PACKAGES`.
- `list_files` — directory browsing only inside a folder explicitly selected by the user through Android's Storage Access Framework.
- Explicit local revoke/change control for the SAF folder grant.

## Goals

- Work when the phone and laptop are on different networks.
- No laptop required after setup.
- Phone initiates an outbound encrypted connection to the Hermes-side relay.
- No routine ADB, IP, port, Termux or raw shell workflow.
- Read-only diagnostics without prompts.
- Mutating and privileged actions require explicit policy/approval.
- Shizuku is the planned first privileged backend; root is not required.
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
                   +-- Shizuku (planned, optional)
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

## Security baseline

The project keeps these non-negotiable rules:

- no raw `run_shell` exposed to the agent;
- no generic command passthrough in the Hermes MCP surface;
- no arbitrary Android intents or arbitrary content URIs;
- no PIN/password capture or unlock automation;
- no root requirement;
- default-deny tool registration;
- read-only tools are narrowly typed and argument-validated;
- destructive actions are not enabled until an approval model exists;
- long-term device credentials are stored in Android Keystore;
- production device transport requires WSS;
- the Hermes-side admin token stays local to the VPS process boundary where possible;
- SAF file access is limited to a directory explicitly chosen by the user.

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
- Python MCP SDK v2 adapter for Hermes

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

No license has been selected yet. Until one is added, normal copyright rules apply.
