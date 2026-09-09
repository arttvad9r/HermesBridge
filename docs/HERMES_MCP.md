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

Create a dedicated APK staging directory:

```bash
sudo mkdir -p /opt/HermesBridge/apks
```

Give write access only to the account/process that is supposed to place APKs there. The MCP install tool accepts a simple file name from this directory, never an arbitrary VPS path.

## Required environment

The relay should normally run on the same VPS:

```text
HERMES_BRIDGE_RELAY_URL=http://127.0.0.1:8080
HERMES_BRIDGE_ADMIN_TOKEN=<relay admin token>
HERMES_BRIDGE_APK_DIR=/opt/HermesBridge/apks
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
      HERMES_BRIDGE_APK_DIR: "/opt/HermesBridge/apks"
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

### `install_apk(device_id, apk_name, replace=true)`

Maps only to `apps.install` and requires Shizuku plus explicit Android-side approval.

`apk_name` is only a simple `.apk` filename located directly inside `HERMES_BRIDGE_APK_DIR`. The MCP adapter rejects raw paths such as `../app.apk`, `/tmp/app.apk`, nested directories and non-APK names.

The transfer/install flow is deliberately split into trust boundaries:

1. MCP streams the selected file over the loopback admin API to the relay staging store.
2. Relay enforces a 200 MiB maximum, computes SHA-256 while streaming and creates a random short-lived download token.
3. Android derives the artifact endpoint only from its configured `wss://.../ws/device` relay origin; arbitrary download URLs are not accepted.
4. Android downloads over HTTPS with redirects disabled.
5. Android verifies exact size and SHA-256.
6. Android PackageManager parses the APK and extracts package name, version and signing-certificate hashes.
7. The phone displays an approval whose fingerprint is bound to verified content metadata: SHA-256, size, package/version, signer hashes and `replace`.
8. After approval, Hermes retries `install_apk` for the same APK. A new relay artifact ID/token is allowed because those are transport details; changed APK content requires a new approval.
9. Shizuku receives only the internally constructed command `pm install [-r] -S <verified size> -`; verified APK bytes are streamed through stdin. Hermes never supplies a shell command or device filesystem path.

Hermes Bridge refuses to replace its own package through this agent tool.

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
- install approval is bound to verified APK content/signing metadata, not an ephemeral download token;
- an approved ticket cannot be replayed after one successful consumption;
- expired approvals require a new user decision.

Current approval is local to the Android app. Telegram/Hermes-side approval routing is planned separately.

## Security boundary

The MCP adapter intentionally has no equivalent of:

```text
run_command(tool, arguments)
run_shell(command)
install_from_url(url)
install_from_path(path)
```

Each public MCP function hardcodes one Android tool name. The Android app applies another independent allowlist and risk policy before execution.

Keep these properties:

- MCP runs locally beside Hermes;
- relay admin API stays loopback-only;
- Android transport uses WSS through the public reverse proxy;
- only the tokenized `/device-artifacts/*` download route is additionally exposed for APK transfer;
- read-only tools execute without approval;
- mutating/privileged tools are separate typed functions;
- no arbitrary shell, intent, content URI, remote URL or raw filesystem path is exposed.

## Smoke test

After relay and MCP are configured:

1. Reload MCP in Hermes.
2. Call `list_devices`.
3. If needed, call `create_pairing_code` and enter it in the Android app.
4. Verify the phone reports connected.
5. Call `device_health` and `list_apps`.
6. Select a folder in the Android app and call `list_files`.
7. Activate/authorize Shizuku in the app.
8. Test `force_stop_app` or `uninstall_app` on a disposable package: approve the displayed card on the phone and retry the same call.
9. Put a disposable test APK directly in `HERMES_BRIDGE_APK_DIR`, call `install_apk`, approve the verified package/version shown on the phone, then retry `install_apk` for the same APK.

A physical-device end-to-end test is still required before privileged actions should be treated as production-ready.
