# Hermes MCP integration

Hermes Bridge exposes a deliberately narrow local stdio MCP server for Hermes Agent. The MCP process talks to the relay admin API over loopback and never exposes a generic Android command selector.

## Install on the Hermes VPS

Use a dedicated virtual environment or `uv` environment:

```bash
cd /opt/HermesBridge/hermes_mcp
python -m venv .venv
. .venv/bin/activate
pip install .
```

This installs:

```text
hermes-bridge-mcp
```

The adapter requires MCP Python SDK v2 (`mcp>=2,<3`).

## Required environment

The relay should normally run on the same VPS:

```text
HERMES_BRIDGE_RELAY_URL=http://127.0.0.1:8080
HERMES_BRIDGE_ADMIN_TOKEN=<relay admin token>
```

Plain HTTP is accepted only for exact loopback hosts (`127.0.0.1`, `::1`, `localhost`). Any non-loopback relay URL must use HTTPS. URLs containing user info, a path, query or fragment are rejected.

## Hermes configuration

Example `~/.hermes/config.yaml` entry:

```yaml
mcp_servers:
  hermes_bridge:
    command: "/opt/HermesBridge/hermes_mcp/.venv/bin/hermes-bridge-mcp"
    env:
      HERMES_BRIDGE_RELAY_URL: "http://127.0.0.1:8080"
      HERMES_BRIDGE_ADMIN_TOKEN: "<relay admin token>"
    enabled: true
```

Reload MCP servers in Hermes after configuration changes.

## Current MCP tools

### `list_devices()`

Lists paired relay devices and whether each is currently connected.

### `create_pairing_code()`

Creates a short-lived one-time pairing code for the Android app.

### `device_health(device_id)`

Maps only to Android tool `device.health`. Returns battery percentage plus memory/storage totals.

### `list_apps(device_id)`

Maps only to `apps.list`. Returns launcher-visible applications. Hermes Bridge does not request `QUERY_ALL_PACKAGES`.

### `list_files(device_id, path_segments=[])`

Maps only to `files.list`. It works only inside the directory tree explicitly selected by the user through Android Storage Access Framework.

`path_segments` is a logical array such as:

```json
["Documents", "Notes"]
```

It is not a raw filesystem path. Both MCP and Android reject traversal markers, separators, excessive depth and oversized segments.

### `uninstall_app(device_id, package_name, keep_data=false)`

Maps only to `apps.uninstall`.

Properties:

- requires Shizuku to be active and authorized;
- requires explicit Android-side approval;
- approval is bound to normalized `packageName + keepData`;
- approval expires and is one-use only;
- Hermes Bridge cannot uninstall itself;
- invalid package names are rejected in both MCP and Android layers.

The first call normally returns a structured `approval_required` result. The phone displays the exact requested operation. After the user approves it, Hermes must retry the same normalized call. Changing the package or `keep_data` requires a new approval.

### `force_stop_app(device_id, package_name)`

Maps only to `apps.forceStop`.

It follows the same approval boundary as uninstall. Hermes Bridge also refuses to force-stop its own package because doing so would terminate the active device connection.

## Approval behavior

Mutating/privileged tools do not execute while the Android approval is pending.

The approval fingerprint includes:

```text
tool name + canonical normalized arguments
```

Consequences:

- approving one package does not authorize another package;
- approving uninstall does not authorize force-stop;
- an approved ticket cannot be replayed after one successful consumption;
- expired approvals require a new user decision.

Current approval is local to the Android app. Telegram/Hermes-side approval routing is planned separately.

## Security boundary

The MCP adapter intentionally has no equivalent of:

```text
run_command(tool, arguments)
run_shell(command)
```

Each public MCP function hardcodes one Android tool name. The Android app applies another independent allowlist and risk policy before execution.

Keep these properties:

- MCP runs locally beside Hermes;
- relay admin API stays loopback-only;
- Android transport uses WSS through the public reverse proxy;
- read-only tools execute without approval;
- mutating/privileged tools are separate typed functions;
- no arbitrary shell, intent, content URI or raw filesystem path is exposed.

## Smoke test

After relay and MCP are configured:

1. Reload MCP in Hermes.
2. Call `list_devices`.
3. If needed, call `create_pairing_code` and enter it in the Android app.
4. Verify the phone reports connected.
5. Call `device_health` and `list_apps`.
6. Select a folder in the Android app and call `list_files`.
7. Activate/authorize Shizuku in the app.
8. Call `force_stop_app` or `uninstall_app` for a disposable test package, approve the displayed card on the phone, then retry the same tool call.

A physical-device end-to-end test is still required before privileged actions should be treated as production-ready.
