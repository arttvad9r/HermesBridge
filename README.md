# Hermes Bridge

Android companion for giving a Hermes agent controlled, remote access to a phone.

The intended UX is deliberately simple:

1. Install one APK.
2. Pair it with Hermes using a one-time code or QR code.
3. Grant only the capabilities you want.
4. Close the app and give tasks to Hermes normally.

Hermes Bridge is **not** intended to expose a general-purpose remote shell. The agent gets a small typed tool surface, read-only by default, with explicit approvals for destructive actions.

> Status: early bootstrap. The Android app shell, device-health readout, pairing-domain model, security policy and project specifications are being implemented. Remote relay pairing is not operational yet.

## Goals

- Work when the phone and laptop are on different networks.
- No laptop required after initial setup.
- Phone initiates an outbound encrypted connection to the Hermes-side relay.
- Simple setup wizard; no routine ADB, IP, port, Termux or MCP configuration.
- Read-only diagnostics without prompts.
- Mutating and privileged actions require explicit policy/approval.
- Shizuku is the planned first privileged backend; root is not required.
- Build on existing Android/MCP work rather than reimplementing it.

## Planned architecture

```text
Telegram / user
      |
      v
Hermes on VPS
      |
      v
Hermes Bridge relay
      ^
      | outbound TLS connection
      |
Hermes Bridge Android app
      |
      +-- Android APIs
      +-- droid-mcp typed tools
      +-- Usage Access
      +-- Accessibility (optional)
      +-- Shizuku (optional privileged backend)
```

See:

- [Product specification](docs/PRODUCT_SPEC.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Security model](docs/SECURITY.md)
- [Relay protocol draft](docs/PROTOCOL.md)
- [Permissions and setup](docs/PERMISSIONS.md)
- [Implementation roadmap](TODO.md)

## Security baseline

The project starts with these non-negotiable rules:

- no raw `run_shell` exposed to the agent;
- no arbitrary Android intents or arbitrary content URIs;
- no PIN/password capture or unlock automation;
- no root requirement;
- default-deny tool registration;
- destructive actions are classified and approved separately;
- long-term device credentials are stored in Android Keystore;
- relay traffic is authenticated and encrypted;
- every privileged/mutating command is auditable.

More detail: [docs/SECURITY.md](docs/SECURITY.md).

## Technology baseline

The first build intentionally follows the known-compatible `droid-mcp` toolchain rather than chasing the newest Android Gradle Plugin immediately:

- Kotlin 2.1.20
- Android Gradle Plugin 8.9.1
- Gradle 8.12
- compile/target SDK 35
- min SDK 30
- Jetpack Compose + Material 3
- Java/Kotlin target 11

`droid-mcp` 0.10.1 is the planned MCP/tool foundation. Its modules will be introduced incrementally behind Hermes Bridge's own capability and approval layer.

## Build

```bash
gradle :app:assembleDebug
```

The bootstrap CI installs Gradle 8.12 explicitly because the repository does not yet contain a Gradle Wrapper binary. Adding and validating the wrapper is tracked in `TODO.md`.

CI runs unit tests, lint and a debug build on every push/PR.

## Repository policy

No license has been selected yet. Until one is added, normal copyright rules apply.
