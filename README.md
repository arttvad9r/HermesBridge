# Hermes Bridge

Security-first Android tools for AI agents — no root, no unrestricted shell, no agent lock-in.

Hermes Bridge lets an AI agent work with an Android phone through a deliberately small typed tool surface. The canonical agent interface is **local MCP over stdio**, so the same bridge can be used by Codex, Claude Code, OpenCode, Cursor, Hermes Agent, or another MCP-capable host.

Hermes Agent is only an optional adapter; the runtime architecture is local and agent-neutral.

> Status: release-candidate development. Core typed tools, Android approvals, pairing/authentication, local Bridge Host, generic MCP adapter, Shizuku UserService backend, idempotent mutation retries, R8 release builds and reproducible APK checks are implemented. Physical-device E2E and battery-impact gates are still required before a stable public release.

## Why this exists

Most Android agent bridges become powerful by exposing shell, arbitrary commands, Accessibility automation, or broad package access. Hermes Bridge takes the opposite approach:

- no agent-facing `run_shell` or arbitrary argv;
- no arbitrary Android intents/content URIs;
- no arbitrary APK URL;
- no `QUERY_ALL_PACKAGES`;
- no root requirement;
- fixed typed operations with bounded results;
- Android-side exact-argument approval for mutating/privileged actions;
- P-256 device identity in Android Keystore;
- local bounded audit history;
- optional Shizuku through a typed Binder/UserService boundary.

## Architecture

```text
Any MCP-capable agent
Codex / Claude / OpenCode / Cursor / Hermes / ...
                 |
                 | local MCP stdio
                 v
        Generic MCP adapter
                 |
                 | loopback HTTP
                 v
        Local Bridge Host
                 ^
                 | authenticated Android channel
                 |
          Android app
                 |
        +--------+---------+
        |                  |
   Android APIs      Optional Shizuku
   SAF / UsageStats  typed privileged ops
```

The agent never receives the Bridge Host admin API directly. The admin API stays on loopback, and Android remains the final policy authority even if the agent or host is compromised.

See [Architecture](docs/ARCHITECTURE.md), [Security model](docs/SECURITY.md), [MCP integration](docs/MCP.md), and [Bridge Host](docs/HOST.md).

## Current tools

Read-only:

- `list_devices`
- `pending_approvals`
- `create_pairing_code`
- `device_health`
- `battery_usage`
- `list_apps`
- `app_usage`
- `app_permissions`
- `permissions_audit`
- `list_files`
- `analyze_files`

Mutating/privileged, with Android-side approval:

- `revoke_app_permission`
- `delete_path`
- `install_apk`
- `uninstall_app`
- `force_stop_app`

Mutation retries can use explicit idempotency keys. Reusing a key for changed arguments fails closed.

## Android security boundary

Hermes Bridge protects its own package from agent-driven replacement, uninstall, force-stop, and permission mutation.

Package enumeration is launcher-scoped. File access is restricted to the Storage Access Framework tree selected by the user. Usage data requires Android Usage Access. Privileged operations require Shizuku and use fixed typed operations only.

The standard release does **not** expose autonomous tap/swipe/screenshot/general UI automation. MediaProjection/UI-control experiments are a separate explicit security boundary and are not part of the agent tool surface.

## Local setup

### 1. Install and start Bridge Host

Requirements: Java 17+ and Python 3.10+.

For regular use:

```bash
./host/install-local.sh
hermesbridge-host
```

For development directly from the checkout, use `./host/run-local-host.sh`.

This creates user-local state and a random admin token:

```text
~/.config/hermesbridge/admin-token
~/.local/state/hermesbridge/
~/.local/share/hermesbridge/apks/
```

No root, systemd, or machine-wide installation is required.

### 2. Connect the generic MCP adapter

`install-local.sh` installs the adapter automatically. Primary executable:

```text
~/.local/bin/android-bridge-mcp
```

The adapter reads the Bridge Host token file automatically. Agent-specific examples are in [docs/MCP.md](docs/MCP.md). Hermes-specific configuration lives under [adapters/hermes](adapters/hermes/README.md).

### 3. Connect Android

The current production Android transport requires a trusted canonical endpoint:

```text
wss://HOST[:PORT]/ws/device
```

How that device-facing WSS endpoint reaches the local Bridge Host is intentionally independent of the agent and core architecture. A user-controlled LAN reverse proxy, private overlay network, or tunnel can provide reachability. The Bridge Host admin routes under `/api/v1/*` must remain loopback-only.

## Build and test

Android/debug build:

```bash
./gradlew :app:assembleDebug
```

Core verification:

```bash
./gradlew --no-daemon \
  :protocol:test \
  :relay:test \
  :app:testDebugUnitTest \
  :app:lintDebug \
  :e2e-fixture:lintDebug \
  :app:assembleDebug \
  :e2e-fixture:assembleDebug
```

MCP adapter tests:

```bash
python -m unittest discover -s mcp_adapter/tests -v
```

Release reproducibility:

```bash
release/verify-reproducible-unsigned-apk.sh
```

## Technology baseline

- Kotlin 2.4.20
- Android Gradle Plugin 8.9.1
- Gradle 8.14.4
- Java/Kotlin target 17
- compile/target SDK 35, min SDK 30
- Jetpack Compose + Material 3
- Ktor WebSockets 3.1.2
- kotlinx.serialization 1.11.0
- AndroidX DocumentFile 1.1.0
- Shizuku API/provider 13.1.5
- Python MCP SDK v2

The narrow Shizuku process-execution pattern is adapted from Apache-2.0 `droid-mcp` source pinned in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). The upstream generic shell surface is not exposed.

## Documentation

- [Product specification](docs/PRODUCT_SPEC.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Security model](docs/SECURITY.md)
- [Generic MCP integration](docs/MCP.md)
- [Local Bridge Host](docs/HOST.md)
- [Protocol](docs/PROTOCOL.md)
- [Permissions and setup](docs/PERMISSIONS.md)
- [Idempotency](docs/IDEMPOTENCY.md)
- [Physical-device E2E](docs/PHYSICAL_E2E.md)
- [Roadmap](TODO.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
