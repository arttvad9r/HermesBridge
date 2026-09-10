# Hermes MCP adapter

This package exposes the local Hermes Bridge relay to Hermes over MCP stdio.

It intentionally exposes only narrow, typed operations. It does **not** expose raw relay command dispatch, arbitrary shell, arbitrary HTTP, or a user-selectable Android tool name.

## Current tools

Read-only:

- `list_devices` — list paired phones and online/offline state.
- `pending_approvals(device_id)` — list short-lived redacted approval notifications for one phone; this cannot approve or deny anything.
- `create_pairing_code` — create a one-time phone pairing code.
- `device_health(device_id)` — battery percentage plus memory/storage totals.
- `battery_usage(device_id)` — bounded parsed Batterystats data since the last charge.
- `list_apps(device_id)` — launcher-visible apps only; no `QUERY_ALL_PACKAGES`.
- `app_usage(device_id, days=30)` — UsageStats for launcher-visible apps; requires Android Usage Access.
- `app_permissions(device_id, package_name)` — requested/granted permission metadata for one launcher-visible app.
- `permissions_audit(device_id)` — bounded scan of launcher-visible apps returning only permissions that are both granted and classified by Android as `dangerous`.
- `list_files(device_id, path_segments=[])` — browse the user-granted SAF tree.
- `analyze_files(device_id, path_segments=[])` — bounded recursive analysis inside the user-granted SAF tree.

Mutating/privileged, with Android-side approval:

- `revoke_app_permission(device_id, package_name, permission_name)` — revoke one currently granted Android-`dangerous` permission from one launcher-visible app; no grant/all-permissions/user-id/flags surface is exposed.
- `delete_path(device_id, path_segments)`.
- `install_apk(device_id, apk_name, replace=true)`.
- `uninstall_app(device_id, package_name, keep_data=false)`.
- `force_stop_app(device_id, package_name)`.

`app_permissions`, `permissions_audit` and permission revoke do not widen Android package visibility: the Android side first derives candidates from the same launcher-visible set used by `list_apps`.

## Install on the Hermes VPS

```bash
python3 -m venv /opt/hermes-bridge-mcp/.venv
/opt/hermes-bridge-mcp/.venv/bin/pip install /path/to/HermesBridge/hermes_mcp
```

The adapter expects:

```text
HERMES_BRIDGE_RELAY_URL=http://127.0.0.1:8080
HERMES_BRIDGE_ADMIN_TOKEN=<same relay admin token>
HERMES_BRIDGE_APK_DIR=/opt/HermesBridge/apks
```

Keep the relay admin endpoint on loopback. Android should connect through public WSS; Hermes MCP should call the relay through `127.0.0.1`.

## Hermes configuration

Example Hermes MCP configuration:

```yaml
mcp_servers:
  android_bridge:
    command: "/opt/hermes-bridge-mcp/.venv/bin/hermes-bridge-mcp"
    args: []
    env:
      HERMES_BRIDGE_RELAY_URL: "http://127.0.0.1:8080"
      HERMES_BRIDGE_ADMIN_TOKEN: "<relay-admin-token>"
      HERMES_BRIDGE_APK_DIR: "/opt/HermesBridge/apks"
```

Then verify with Hermes:

```bash
hermes mcp test android_bridge
```

The resulting Hermes tools are expected to be namespaced by Hermes under the configured MCP server name.

## Optional Telegram approval notifications

`hermes-bridge-telegram-approvals` is an optional one-way notifier for the same redacted pending-approval feed exposed by `pending_approvals`. It does **not** receive Telegram updates, register webhooks, add inline buttons, approve/deny a ticket, or send an Android command. The user still has to approve or deny the exact request locally on the phone.

Configure one notifier process for one paired device:

```text
HERMES_BRIDGE_RELAY_URL=http://127.0.0.1:8080
HERMES_BRIDGE_ADMIN_TOKEN=<same relay admin token>
HERMES_BRIDGE_TELEGRAM_BOT_TOKEN=<BotFather token>
HERMES_BRIDGE_TELEGRAM_CHAT_ID=<numeric chat id>
HERMES_BRIDGE_TELEGRAM_DEVICE_ID=device_<uuid>
HERMES_BRIDGE_TELEGRAM_POLL_SECONDS=10
```

Use a numeric private/group/channel chat ID that you control. Username destinations such as `@channel_username` are deliberately rejected so an approval notification cannot be redirected to a public Telegram username by configuration alone.

Run it under the same local process supervisor used for Hermes-side helpers:

```bash
/opt/hermes-bridge-mcp/.venv/bin/hermes-bridge-telegram-approvals
```

Each message contains only the allowlisted tool name, its fixed risk class and approximate remaining TTL. It deliberately omits the Android approval ID, target package/permission/path, APK metadata, local approval-card summary and raw command arguments. The notifier rejects approval expiries beyond the same ten-minute maximum accepted by the relay. The bot token is used only in the fixed HTTPS request to `api.telegram.org` and is never intentionally printed. Delivery is best-effort with at-least-once retry semantics: in-memory approval-ID deduplication suppresses normal repeats, but a process restart or an ambiguous transport failure after Telegram accepted a message can produce a duplicate informational notification.

Do not add Telegram callback buttons or a VPS-side approve/deny endpoint. A Telegram/VPS credential on the same server is not an independent factor for an Android-created approval ticket; the Android approval boundary remains authoritative.

## Safety model

The MCP adapter is a second allowlist in addition to the Android app allowlist:

1. Hermes can only see MCP functions defined in this package.
2. Every MCP function hardcodes exactly one Android tool name.
3. Android independently checks its own typed router/registry and risk policy before execution.
4. Read-only package tools remain launcher-scoped and do not request `QUERY_ALL_PACKAGES`.
5. Permission audit output is bounded and returns Android's platform classification, not a custom risk score.
6. Permission changes are revoke-only, exact-target and require Android-side approval; Android derives the target user locally.
7. The relay admin API remains local to the VPS.
8. Destructive actions are separate typed MCP tools with Android-side exact-target approval; there is no generic `run_command(tool, args)` entry point.
9. Remote approval/Telegram routing is informational only and cannot consume or resolve an Android approval ticket.
