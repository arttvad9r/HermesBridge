# Generic MCP adapter

`mcp_adapter/` is the canonical AI-agent boundary for TypedDroid. It is a local MCP stdio server and is not tied to Hermes Agent or a particular model provider.

The adapter exposes fixed tools plus a full-control TRUSTED surface (`run_shell`, structured activity/settings/AppOps/diagnostic tools, and UI perception/control). SAFE-mode phones reject that authority locally. There is no arbitrary HTTP request or arbitrary APK URL surface.

## Install

```bash
python3 -m venv ~/.local/share/typeddroid/mcp-venv
~/.local/share/typeddroid/mcp-venv/bin/pip install ./mcp_adapter
```

Primary command:

```text
typeddroid-mcp
```

`typed-droid-mcp` remains as a compatibility alias for older local configurations.

## Local configuration

Start the local Bridge Host first:

```bash
./host/run-local-host.sh
```

Defaults:

```text
TYPED_DROID_HOST_URL=http://127.0.0.1:8080
admin token file: ~/.config/typeddroid/admin-token
APK directory:    ~/.local/share/typeddroid/apks
```

Normally no token is copied into the agent configuration. The MCP process reads the same local token file created by Bridge Host. Environment overrides remain available through `TYPED_DROID_ADMIN_TOKEN`, `TYPED_DROID_ADMIN_TOKEN_FILE`, `TYPED_DROID_HOST_URL`, and `TYPED_DROID_APK_DIR`.

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
3. The Bridge Host admin API stays loopback-only.
4. Raw shell is a separate TRUSTED-only capability and is denied by the phone in SAFE mode.
5. Package visibility stays launcher-scoped; `QUERY_ALL_PACKAGES` is not used.
6. APK installation accepts only a simple file name from the dedicated local staging directory, then Android verifies the actual artifact before approval.
7. Approval notifications are informational. A compromised agent/host cannot turn them into Android approval.

See `../docs/MCP.md`, `../docs/SECURITY.md`, and `../docs/IDEMPOTENCY.md`.
