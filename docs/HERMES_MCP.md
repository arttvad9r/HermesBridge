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

This installs the executable:

```text
hermes-bridge-mcp
```

The adapter requires the MCP Python SDK v2 (`mcp>=2,<3`).

## Required environment

The relay should normally run on the same VPS:

```text
HERMES_BRIDGE_RELAY_URL=http://127.0.0.1:8080
HERMES_BRIDGE_ADMIN_TOKEN=<relay admin token>
```

Plain HTTP is accepted only for exact loopback hosts (`127.0.0.1`, `::1`, `localhost`). Any non-loopback relay URL must use HTTPS. URLs containing user info, a path, query or fragment are rejected.

The admin token must be at least 24 characters and should be the same secret configured for the relay service.

## Hermes configuration

Hermes Agent supports local stdio MCP servers through `~/.hermes/config.yaml`. Add a dedicated entry:

```yaml
mcp_servers:
  hermes_bridge:
    command: "/opt/HermesBridge/hermes_mcp/.venv/bin/hermes-bridge-mcp"
    env:
      HERMES_BRIDGE_RELAY_URL: "http://127.0.0.1:8080"
      HERMES_BRIDGE_ADMIN_TOKEN: "<relay admin token>"
    enabled: true
```

Hermes prefixes discovered MCP tools with the server name, so the tools appear under names such as `mcp_hermes_bridge_device_health`.

After changing MCP config, reload MCP servers in Hermes with `/reload-mcp`, start a new session, or restart Hermes.

Hermes also supports `hermes mcp add` for custom servers. The direct `config.yaml` form is documented here because it makes the command path and environment boundary explicit.

## Current MCP tools

### `list_devices()`

Lists paired relay devices and whether they are currently connected.

### `create_pairing_code()`

Creates a short-lived one-time pairing code for the Android app.

### `device_health(device_id)`

Maps only to Android tool:

```text
device.health
```

Returns battery percentage plus memory/storage totals.

### `list_apps(device_id)`

Maps only to Android tool:

```text
apps.list
```

Returns launcher-visible applications. Hermes Bridge does not request `QUERY_ALL_PACKAGES`.

### `list_files(device_id, path_segments=[] )`

Maps only to Android tool:

```text
files.list
```

It works only after the user selects a directory in the Android app through the Storage Access Framework.

`path_segments` is an array such as:

```json
["Documents", "Notes"]
```

It is not a raw filesystem path. Both the MCP adapter and Android app reject traversal markers, path separators, excessive depth and oversized segments.

The tool is read-only. It cannot open arbitrary content URIs, write, rename or delete files.

## Security boundary

The MCP adapter intentionally has no public equivalent of:

```text
run_command(tool, arguments)
```

Each public MCP function hardcodes one Android tool name. A caller cannot turn `device_health()` or `list_files()` into `run_shell`, uninstall, arbitrary intent execution, or another unregistered operation by changing arguments.

Keep these properties:

- MCP runs locally beside Hermes;
- relay admin API stays loopback-only;
- Android device transport uses WSS through the public reverse proxy;
- only read-only tools are currently exposed;
- future mutating/privileged tools must be separate typed functions and require the approval layer first.

## Smoke test

After the relay is running and the MCP entry is configured:

1. Reload MCP in Hermes.
2. Call the device-list tool.
3. If the phone is not paired, create a pairing code and enter it in the Android app.
4. Verify the device reports connected.
5. Call device health.
6. Call app listing.
7. Select a folder in the Android app and call file listing with an empty segment list to list the granted root.

If `files.list` returns `file_access_not_configured`, the Android SAF folder has not yet been selected or its persisted grant is no longer valid.
