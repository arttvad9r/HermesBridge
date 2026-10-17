# Local Bridge Host

The Bridge Host runs on the same computer as the AI agent. It is agent-neutral and does not require Hermes Agent, systemd, root, or a public admin API.

## Install for regular use

From a source checkout, install the local runtime and generic MCP adapter without root:

```bash
./host/install-local.sh
```

This creates:

```text
~/.local/bin/typeddroid-host
~/.local/share/typeddroid/host-runtime/
~/.local/bin/typeddroid-mcp
```

The installer does not edit Codex, Claude, OpenCode, Cursor, Hermes, or any other agent configuration. Point the chosen MCP host at the installed `typeddroid-mcp` executable.

## Start from the source checkout

Requirements: Java 17+ and Python 3.10+.

```bash
./host/run-local-host.sh
```

On first start the script:

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
