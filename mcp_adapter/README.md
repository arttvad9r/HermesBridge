# Generic MCP adapter

`mcp_adapter/` is the canonical AI-agent boundary for TypedDroid. It is a local MCP stdio server and is not tied to Hermes Agent or a particular model provider.

The adapter exposes fixed tools plus a full-control TRUSTED surface (`run_shell`, structured activity/settings/AppOps/diagnostic tools, and UI perception/control). SAFE-mode phones reject that authority locally. There is no arbitrary HTTP request or arbitrary APK URL surface.

## Install

From a TypedDroid checkout, use the single agent-side installer:

```bash
./install.sh
```

Primary command:

```text
typeddroid-mcp
```

`typeddroid-mcp` starts or reuses the loopback relay automatically. Concurrent MCP clients share one relay process; the last managed MCP process shuts it down. The relay is an implementation detail under `~/.local/share/typeddroid/relay-runtime/`, not a second command the user must run.

Defaults:

```text
internal relay:    http://127.0.0.1:8080
admin token file: ~/.config/typeddroid/admin-token
APK directory:    ~/.local/share/typeddroid/apks
transfer dir:     ~/.local/share/typeddroid/transfers
```

Normally no token is copied into agent configuration. Environment overrides remain available for development or an explicitly external relay. Setting `TYPED_DROID_HOST_URL` or legacy `TYPED_DROID_RELAY_URL` disables managed-local-runtime startup.

## Tools

Read-only:

- `list_devices`
- `pending_approvals(device_id)` — redacted notifications only; cannot approve/deny
- `create_pairing_code`
- `device_health(device_id)`
- `battery_usage(device_id)`
- `list_apps(device_id)`
- `app_usage(device_id, days=30)`
- `app_permissions(device_id, package_name)`
- `permissions_audit(device_id)`
- `list_files(device_id, path_segments=[])`
- `analyze_files(device_id, path_segments=[])`

TRUSTED full-control tools include `device_capabilities`, `run_shell`, structured system/appops/settings/process/logcat tools, typed shell-visible filesystem tools, `push_file` / `pull_file` bulk streaming, UI perception/control, and notification tools: `list_notifications`, `dismiss_notification`, `open_notification`, `notification_action`, and `reply_notification`. Notification content is explicitly external/untrusted data, and Android Notification Access must be enabled on the phone.

Bulk transfer files are confined to `TYPED_DROID_TRANSFER_DIR` (default `~/.local/share/typeddroid/transfers`). `push_file` reads only a simple direct-child filename from that directory; `pull_file` writes only there and does not replace an existing local file unless `overwrite=true` is explicit. The data plane streams raw bytes with a 512 MiB per-transfer cap and size/SHA-256 verification instead of placing file contents in MCP/control JSON.

Mutating/privileged, always subject to Android-side policy/approval:

- `revoke_app_permission(...)`
- `delete_path(...)`
- `install_apk(...)`
- `uninstall_app(...)`
- `force_stop_app(...)`

Mutating tools accept an optional `idempotency_key` for an exact retry after `approval_required` or an unknown transport outcome. Reusing a key with changed arguments fails closed.

## Safety boundary

The MCP layer is a second allowlist in addition to Android:

1. Each MCP function maps to one fixed Android tool.
2. Android independently validates schemas, risk and exact approval arguments.
3. The internal relay admin API stays loopback-only.
4. Raw shell is a separate TRUSTED-only capability and is denied by the phone in SAFE mode.
5. Package visibility stays launcher-scoped; `QUERY_ALL_PACKAGES` is not used.
6. APK installation accepts only a simple file name from the dedicated local staging directory, then Android verifies the actual artifact before approval.
7. Approval notifications are informational. A compromised agent/host cannot turn them into Android approval.

See `../docs/MCP.md`, `../docs/SECURITY.md`, and `../docs/IDEMPOTENCY.md`.
