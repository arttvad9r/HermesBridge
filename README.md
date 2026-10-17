# TypedDroid

Full-control Android bridge for AI agents with explicit SAFE/TRUSTED modes — one Android app, an agent-neutral MCP interface, and no mandatory Shizuku or root runtime.

TypedDroid lets an MCP-capable agent use an Android phone through a local, user-controlled capability layer. Common operations remain structured tools, while a pairing explicitly switched to **TRUSTED** may use raw Android shell access through a TypedDroid-owned privileged helper. The same MCP server can be used by Codex, Claude Code, OpenCode, Cursor, Hermes Agent, or another MCP-capable host.

Hermes Agent is only an optional adapter; the protocol and privilege model are agent-neutral.

> Status: active full-control development on the dedicated development branch. SAFE/TRUSTED policy, generic MCP, repository-owned Android 11+ ADB pairing/transport, shell-v2, a shell-UID `app_process` helper, TRUSTED `run_shell`, typed privileged app operations, and typed UI perception/control are implemented on the owned backend. Wireless-Debugging onboarding and helper bootstrap are present in the Android UI. Physical-device interoperability/reboot/OEM tests are still required before stable release.

## Why this exists

Giving an autonomous agent ordinary `adb shell` is powerful but coarse. TypedDroid aims to keep that power while making the grant explicit and reusable:

- every new agent pairing starts in **SAFE** mode;
- SAFE exposes structured, bounded capabilities and denies raw shell;
- the user may explicitly switch that pairing to **TRUSTED** for the full-control surface;
- `run_shell` is then a named capability rather than an accidental arbitrary-command escape hatch;
- the Android app owns the ADB identity, pairing, policy, audit and privileged-helper lifecycle;
- the privileged path requires no companion privilege app such as Shizuku, Termux or Tasker;
- root is optional and is not required for the normal shell-UID backend;
- lockscreen credential automation remains outside the trusted grant;
- the agent-facing protocol is MCP rather than a vendor-specific integration.


## Architecture

```text
Any MCP-capable agent
Codex / Claude / OpenCode / Cursor / Hermes / ...
                 |
                 | local MCP stdio
                 v
          typeddroid-mcp
                 |
                 | local Bridge Host / device transport
                 v
          TypedDroid Android app
                 |
        +--------+-------------------+
        |                            |
   Android APIs                owned privileged path
 SAF / UsageStats             Wireless ADB bootstrap
                                     |
                                     v
                            TypedDroid app_process helper
                                (Android shell UID)
                                     |
                           authenticated loopback-only RPC

```

Wireless Debugging is used to establish/restore the shell-UID helper, not as the intended long-lived agent transport. The normal agent↔phone connection remains TypedDroid's authenticated device channel, so future Wi-Fi↔mobile-network handover is independent of ADB networking.

See [Architecture](docs/ARCHITECTURE.md), [Security model](docs/SECURITY.md), [MCP integration](docs/MCP.md), and [full-control roadmap](docs/FULL_CONTROL_ROADMAP.md).

## Current MCP tools

Read-only / structured:

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

Mutating/privileged typed tools, currently using the existing approval model:

- `revoke_app_permission`
- `delete_path`
- `install_apk`
- `uninstall_app`
- `force_stop_app`

Full-control (all denied in SAFE and available only to a pairing explicitly switched to TRUSTED while the owned privileged helper is active):

- `device_capabilities` — inspect trust/backend capability state;
- `run_shell` — unrestricted Android shell UID command execution;
- `open_app` / `activity_start` — launcher app and bounded component/action/data activity starts;
- `settings_get` / `settings_put` — Android system/secure/global settings;
- `appops_get` / `appops_set` — package AppOps inspection/control;
- `process_list` / `logcat_read` — bounded process and Android log diagnostics;
- `list_notifications` / `dismiss_notification` / `open_notification` — active notification inspection/control through Android Notification Access;
- `notification_action` / `reply_notification` — invoke advertised notification actions, including free-form RemoteInput replies;
- `push_file` / `pull_file` — stream files between the dedicated agent-side transfer directory and shell-visible Android storage without base64/control-frame payloads;
- `fs_stat` / `fs_list` / `fs_read` / `fs_search` — shell-visible filesystem inspection;
- `fs_write` / `fs_mkdir` / `fs_copy` / `fs_move` / `fs_delete` — chunked mutation in `/sdcard`, `/storage/emulated/0`, and `/data/local/tmp`;
- `ui_hierarchy` / `ui_screenshot` — bounded UI perception;
- `ui_tap` / `ui_swipe` / `ui_long_press` — coordinate input;
- `ui_wait_for_text` — wait for a text signal in the UI hierarchy;
- `ui_text` / `ui_key` — focused text and key-event input.

Mutation retries can use explicit idempotency keys. Reusing a key for changed arguments fails closed.

## Android authority model

Agent trust and Android privilege are separate axes. A TRUSTED agent does not create shell authority by itself: the phone must also have an active privileged backend. Conversely, an active shell-UID helper does not give a SAFE agent raw shell access.

Typed operations continue to protect the TypedDroid package and keep their existing validation/approval behavior. The TRUSTED raw-shell capability is intentionally broader: once granted, it has the authority Android gives the `shell` UID, so restrictions such as launcher-only package enumeration or SAF-only paths do not describe the maximum authority of that specific capability.

The current full-control work does not claim root, lockscreen bypass, or access that Android's shell UID itself does not have. UI perception/control now uses typed shell-UID helper operations; protected/secure Android surfaces may still hide content or reject input.

## Local setup

### 1. Install and start Bridge Host

Requirements: Java 17+ and Python 3.10+.

For regular use:

```bash
./host/install-local.sh
typeddroid-host
```

For development directly from the checkout, use `./host/run-local-host.sh`.

This creates user-local state and a random admin token:

```text
~/.config/typeddroid/admin-token
~/.local/state/typeddroid/
~/.local/share/typeddroid/apks/
```

No root, systemd, or machine-wide installation is required.

### 2. Connect the generic MCP adapter

`install-local.sh` installs the adapter automatically. Primary executable:

```text
~/.local/bin/typeddroid-mcp
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
- Python MCP SDK v2

Historical Apache-2.0 provenance for early package-command behavior is retained in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). TypedDroid no longer ships or requires the Shizuku API/provider.

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
