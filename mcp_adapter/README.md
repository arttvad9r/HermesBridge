# Generic MCP adapter

`mcp_adapter/` is the canonical AI-agent boundary for Hermes Bridge. It is a local MCP stdio server and is not tied to Hermes Agent or a particular model provider.

The adapter exposes only fixed typed tools. There is no generic command selector, raw shell, arbitrary HTTP request, arbitrary Android intent, or arbitrary APK URL.

## Install

```bash
python3 -m venv ~/.local/share/hermesbridge/mcp-venv
~/.local/share/hermesbridge/mcp-venv/bin/pip install ./mcp_adapter
```

Primary command:

```text
android-bridge-mcp
```

`hermes-bridge-mcp` remains as a compatibility alias for older local configurations.

## Local configuration

Start the local Bridge Host first:

```bash
./host/run-local-host.sh
```

Defaults:

```text
HERMES_BRIDGE_HOST_URL=http://127.0.0.1:8080
admin token file: ~/.config/hermesbridge/admin-token
APK directory:    ~/.local/share/hermesbridge/apks
```

Normally no token is copied into the agent configuration. The MCP process reads the same local token file created by Bridge Host. Environment overrides remain available through `HERMES_BRIDGE_ADMIN_TOKEN`, `HERMES_BRIDGE_ADMIN_TOKEN_FILE`, `HERMES_BRIDGE_HOST_URL`, and `HERMES_BRIDGE_APK_DIR`.

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
4. The agent never receives a raw shell/argv surface.
5. Package visibility stays launcher-scoped; `QUERY_ALL_PACKAGES` is not used.
6. APK installation accepts only a simple file name from the dedicated local staging directory, then Android verifies the actual artifact before approval.
7. Approval notifications are informational. A compromised agent/host cannot turn them into Android approval.

See `../docs/MCP.md`, `../docs/SECURITY.md`, and `../docs/IDEMPOTENCY.md`.
