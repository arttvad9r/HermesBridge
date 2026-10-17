# Internal local relay

The local relay runs on the same computer as the AI agent, but it is no longer a normal user-facing component. `typeddroid-mcp` starts/reuses it automatically and coordinates its lifetime across concurrent MCP processes. The standalone script remains only for development/debugging.

## Install for regular use

From a source checkout, install the complete agent side without root:

```bash
./install.sh
```

This creates:

```text
~/.local/bin/typeddroid-mcp
~/.local/share/typeddroid/relay-runtime/
```

The installer does not edit Codex, Claude, OpenCode, Cursor, Hermes, or any other agent configuration. Point the chosen MCP host at the single installed `typeddroid-mcp` executable. The old `typeddroid-host` launcher is removed during migration.

## Standalone relay debugging

Requirements: Java 17+ and Python 3.10+.

For relay development only, it can still be started explicitly:

```bash
./host/run-local-host.sh
```

The script:

- builds the relay distribution from source if needed;
- creates a random local admin token without printing it;
- stores state under the XDG user directories;
- creates dedicated local APK and generic file-transfer staging directories;
- binds the admin/relay process to `127.0.0.1:8080` only.

Default paths:

```text
~/.config/typeddroid/admin-token
~/.local/state/typeddroid/
~/.local/share/typeddroid/apks/
~/.local/share/typeddroid/transfers/
```

The generic MCP adapter reads the same token file automatically, so local MCP hosts normally need no secret in their configuration.

## Device ingress

Android still initiates the device connection. The current production transport requires a trusted `wss://.../ws/device` endpoint. How that endpoint reaches the local Bridge Host is deliberately outside the agent/core boundary: a LAN reverse proxy, private overlay network, or another user-controlled tunnel may be used.

The admin API must stay loopback-only. Never publish `/api/v1/*` to the network.

A future local-device transport may remove the WSS ingress requirement, but it must preserve the same typed operations and Android-side approval boundary.
