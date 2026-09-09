# Hermes Bridge

Android companion for giving a Hermes agent controlled, remote access to a phone.

The intended UX is deliberately simple:

1. Install one APK.
2. Pair it with Hermes using a one-time code.
3. Grant only the capabilities you want through the guided setup.
4. Close the app and give tasks to Hermes normally.

Hermes Bridge is **not** intended to expose a general-purpose remote shell. The agent gets a small typed tool surface, read-only by default, with exact-argument approvals for mutating/privileged actions.

> Status: functional development prototype. Authenticated outbound relay pairing, automatic reconnect, guided setup, safe re-pair/revocation, Hermes MCP integration, read-only diagnostics/app metadata/files, bounded permission and storage audits, approved dangerous-permission revocation, SAF deletion and Shizuku-backed install/uninstall/force-stop operations are implemented. Physical phone/VPS end-to-end validation is still required before treating privileged operations as production-ready.

## Current capabilities

- P-256 device identity stored in Android Keystore.
- One-time pairing and challenge/response reconnect authentication.
- Persistent outbound WSS connection from Android to the relay.
- Foreground service with reboot/package-update recovery.
- Guided first-run setup instead of exposing all technical settings at once.
- Confirmed phone-side self-revoke plus localhost admin revoke; clean re-pair without reinstalling the APK.
- Relay deployment examples for systemd + TLS reverse proxy.
- Local stdio MCP adapter for Hermes.
- `device_health` — battery percentage plus memory and storage totals.
- `battery_usage` — bounded read-only Android Batterystats data since the last charge, parsed locally from fixed `dumpsys batterystats -c --charged`; raw dumpsys is never returned to Hermes.
- `list_apps` — launcher-visible apps without `QUERY_ALL_PACKAGES`.
- `app_usage` — bounded UsageStats for launcher-visible apps after the user enables Android Usage Access.
- `app_permissions` — requested/granted permission metadata for one launcher-visible app.
- `permissions_audit` — bounded launcher-only scan returning permissions that are both granted and classified by Android as `dangerous`, with explicit truncation state.
- `revoke_app_permission` — revoke one currently granted Android-`dangerous` runtime permission from one launcher-visible app after exact one-use local approval; Android user ID is derived on-device and Hermes cannot provide `pm` flags.
- `list_files` — directory browsing only inside a folder explicitly selected through Android Storage Access Framework.
- `analyze_files` — bounded recursive SAF analysis with total size, counts, truncation status and largest-file summaries.
- `delete_path` — deletion of one validated SAF file/directory after local approval bound to the target's current metadata; the granted root itself is protected.
- Explicit local revoke/change control for the SAF folder grant.
- Shizuku detection/authorization status in the Android app.
- One-use, expiring approvals bound to canonical tool arguments.
- `install_apk` — APK from a dedicated VPS staging directory, streamed through a short-lived relay artifact, then verified on Android by size/SHA-256/package/version/signing certificates before approval and Shizuku installation.
- `uninstall_app` — Shizuku-backed package uninstall after local approval.
- `force_stop_app` — Shizuku-backed force-stop after local approval.
- Hermes Bridge protects its own package from install replacement, uninstall, force-stop and agent-driven permission changes.

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
                   | outbound WSS + tokenized artifact HTTPS
                   |
             Android app
                   |
                   +-- Android APIs
                   +-- PackageManager (launcher-scoped visibility)
                   +-- UsageStats (optional special access)
                   +-- Storage Access Framework (user-selected tree)
                   +-- approval policy
                   +-- Shizuku (optional typed privileged/read-only commands)
                   +-- Accessibility (planned, optional)
```

The relay admin API is intended to remain on localhost beside Hermes. The public TLS surface is limited to health, the device WebSocket, and short-lived token-authenticated artifact downloads used for APK installation.

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
- no arbitrary APK URL or arbitrary VPS/device filesystem path for installation;
- no arbitrary arguments for Batterystats diagnostics;
- no PIN/password capture or unlock automation;
- no root requirement;
- default-deny tool routing/registration;
- read-only tools are narrowly typed, argument-validated and output-bounded;
- app metadata/audits stay launcher-scoped and do not request `QUERY_ALL_PACKAGES`;
- permission audit uses Android's own `dangerous` protection classification rather than an invented risk score;
- agent-driven permission changes are revoke-only, launcher-scoped, limited to currently granted Android-`dangerous` permissions and exact locally approved targets;
- privileged/mutating commands require one-use exact-argument approval;
- APK install approval is bound to verified content/package/signing metadata rather than transport tokens;
- SAF deletion approval is bound to the validated target path and current metadata;
- long-term device credentials are stored in Android Keystore;
- production device transport requires WSS;
- the Hermes-side admin token stays local to the VPS process boundary where possible;
- SAF file access is limited to a directory explicitly chosen by the user;
- privileged package operations validate package names and protect Hermes Bridge itself;
- Shizuku diagnostics return bounded parsed structures rather than unrestricted command output.

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

The narrow Shizuku process-execution pattern is adapted from Apache-2.0 `droid-mcp` source pinned in `THIRD_PARTY_NOTICES.md`; no JitPack runtime/build dependency is used. Batterystats parsing is Hermes Bridge code based on Android's checkin-format output.

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
