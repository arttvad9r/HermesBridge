# Hermes MCP adapter

This package exposes the local Hermes Bridge relay to Hermes over MCP stdio.

It intentionally exposes only narrow, typed operations. It does **not** expose raw relay command dispatch, arbitrary shell, arbitrary HTTP, or a user-selectable Android tool name.

## Current tools

- `list_devices` — list paired phones and online/offline state.
- `create_pairing_code` — create a one-time phone pairing code.
- `device_health(device_id)` — invoke the fixed Android `device.health` allowlisted tool.

## Install on the Hermes VPS

```bash
python3 -m venv /opt/hermes-bridge-mcp/.venv
/opt/hermes-bridge-mcp/.venv/bin/pip install /path/to/HermesBridge/hermes_mcp
```

The adapter expects:

```text
HERMES_BRIDGE_RELAY_URL=http://127.0.0.1:8080
HERMES_BRIDGE_ADMIN_TOKEN=<same relay admin token>
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
```

Then verify with Hermes:

```bash
hermes mcp test android_bridge
```

The resulting Hermes tools are expected to be namespaced by Hermes under the configured MCP server name.

## Safety model

The MCP adapter is a second allowlist in addition to the Android app allowlist:

1. Hermes can only see MCP functions defined in this package.
2. `device_health` hardcodes the relay command name to `device.health`.
3. Android independently checks its own tool registry before execution.
4. The relay admin API remains local to the VPS.
5. Future destructive actions should be separate MCP tools with explicit confirmation policy; they should never be introduced through a generic `run_command(tool, args)` entry point.
