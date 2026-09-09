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

This installs `hermes-bridge-mcp`. The adapter requires MCP Python SDK v2 (`mcp>=2,<3`).

Create the APK staging directory used only by `install_apk`:

```bash
sudo mkdir -p /opt/HermesBridge/apks
```

## Required environment

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

## Read-only MCP tools

### `list_devices()`

Lists paired relay devices and whether each is currently connected.

### `create_pairing_code()`

Creates a short-lived one-time pairing code for the Android app.

### `device_health(device_id)`

Maps only to Android tool `device.health`. Returns current battery percentage plus memory/storage totals.

### `battery_usage(device_id)`

Maps only to Android tool `battery.usage`. It accepts no diagnostic or shell arguments.

Android runs exactly:

```text
dumpsys batterystats -c --charged
```

through Shizuku. `-c` requests the current statistics in checkin-format output without using the real `--checkin` path. `--charged` scopes the data to since the last charge. Execution time and stdout/stderr sizes are bounded, and Android parses only supported sections rather than returning the raw dump.

Returned data includes, when Android provides it:

- checkin format version;
- battery capacity and estimated drained power in mAh;
- bounded system power-use items;
- bounded top UIDs by estimated mAh;
- bounded top partial wakelocks and package mappings.

This is accumulated Batterystats data, not instantaneous current/wattage. The tool never runs `--reset` and never accepts arbitrary dumpsys arguments.

### `list_apps(device_id)`

Maps only to `apps.list`. Returns launcher-visible applications. Hermes Bridge does not request `QUERY_ALL_PACKAGES`.

### `app_usage(device_id, days=30)`

Maps only to `apps.usage`. `days` must be an integer from 1 to 365 and defaults to 30.

The phone must have Android Usage Access enabled for Hermes Bridge. Results are intersected with the same launcher-visible set used by `list_apps`, so Usage Access does not expand the agent's package visibility.

### `app_permissions(device_id, package_name)`

Maps only to `apps.permissions`.

The Android side first verifies that `package_name` is already present in the launcher-visible app set. Only then does it read `PackageInfo` permission metadata. Returned fields include requested permissions, current granted state, Android protection classification, permission group and relevant request flags.

`dangerous=true` means Android itself classifies the permission with the `dangerous` base protection level. Hermes Bridge does not invent a custom risk score or claim that a granted permission is unnecessary.

### `permissions_audit(device_id)`

Maps only to `apps.permissionsAudit` and provides a bounded overview for questions such as "which visible apps currently hold dangerous permissions?".

The audit:

- scans only launcher-visible apps;
- scans at most 200 apps per call;
- returns only permissions that are both currently granted and Android-classified as `dangerous`;
- returns at most 30 app findings and 10 permission records per returned app;
- reports `scanTruncated` and `resultTruncated` explicitly;
- never requests `QUERY_ALL_PACKAGES`;
- does not inspect hidden/system-only packages through another API;
- does not modify or revoke any permission.

The result also reports visible/scanned/skipped/matched counts and the total number of dangerous granted permissions found within the scanned set.

### `list_files(device_id, path_segments=[])`

Maps only to `files.list`. It works only inside the directory tree explicitly selected by the user through Android Storage Access Framework.

`path_segments` is a logical array such as:

```json
["Documents", "Notes"]
```

It is not a raw filesystem path. Both MCP and Android reject traversal markers, separators, excessive depth and oversized segments.

### `analyze_files(device_id, path_segments=[])`

Maps only to `files.analyze` and is read-only. Android recursively analyzes only the granted SAF subtree with hard traversal/output limits and returns `truncated` when incomplete.

## Mutating / privileged MCP tools

### `revoke_app_permission(device_id, package_name, permission_name)`

Maps only to Android tool `apps.revokePermission`.

This is deliberately a revoke-only tool. It cannot grant permissions, revoke all permissions, choose an Android user, add `pm` flags or run another package-manager command.

Before approval Android verifies that:

- `package_name` is launcher-visible;
- the app currently requests `permission_name`;
- Android classifies that permission with the `dangerous` base protection level;
- the permission is currently granted;
- the target is not Hermes Bridge itself.

The Android user ID is derived locally from the bridge process UID; Hermes cannot supply it. Approval is bound to canonical `packageName + permissionName + userId` and is one-use. After approval Hermes retries the same logical call. Shizuku receives only the internally built command:

```text
pm revoke --user <derived-user-id> <package> <permission>
```

If the permission is already revoked when the command is retried, the tool returns idempotent success without issuing another privileged command.

### `delete_path(device_id, path_segments)`

Maps only to `files.delete`. The target must be below the SAF root; the granted root itself cannot be deleted. Approval is bound to normalized path plus current target metadata, so a changed target requires a new approval.

### `install_apk(device_id, apk_name, replace=true)`

Maps only to `apps.install` and requires Shizuku plus explicit Android-side approval.

`apk_name` must be a simple `.apk` filename directly inside `HERMES_BRIDGE_APK_DIR`; arbitrary VPS paths and URLs are rejected.

The flow is:

1. MCP streams the selected APK over the loopback admin API to relay staging.
2. Relay enforces the size limit, computes SHA-256 and creates a short-lived random download token.
3. Android derives the artifact URL only from its configured relay origin and downloads over HTTPS without redirects.
4. Android verifies exact size and SHA-256, then parses package/version/signing certificates.
5. Approval is bound to verified content/package/signing metadata plus `replace`.
6. After approval Hermes retries the same logical install.
7. Shizuku receives only an internally constructed `pm install [-r] -S <size> -`; verified bytes are streamed on stdin.

Hermes Bridge refuses to replace itself through this tool.

### `uninstall_app(device_id, package_name, keep_data=false)`

Maps only to `apps.uninstall`. It requires Shizuku and a one-use Android-side approval bound to normalized `packageName + keepData`. Hermes Bridge cannot uninstall itself.

### `force_stop_app(device_id, package_name)`

Maps only to `apps.forceStop`. It requires Shizuku and exact-argument approval. Hermes Bridge cannot force-stop itself because that would terminate the active bridge process.

## Approval behavior

Read-only tools execute without approval but remain fixed, validated and bounded. Mutating/privileged tools do not execute while approval is pending.

Approval fingerprints bind:

```text
tool name + canonical normalized arguments
```

Additionally, APK install approval binds verified content/package/signing metadata, SAF deletion binds current target metadata, and permission revoke binds package + permission + derived Android user. Approved tickets expire and are one-use only.

Current approval is local to the Android app. Telegram/Hermes-side approval routing is planned separately.

## Security boundary

The MCP adapter intentionally has no equivalent of:

```text
run_command(tool, arguments)
run_shell(command)
run_dumpsys(args)
install_from_url(url)
install_from_path(path)
delete_raw_path(path)
pm_permission(command, flags)
```

Each public MCP function hardcodes one Android tool name. Android applies another typed router/registry and risk policy before execution.

Keep these properties:

- MCP runs locally beside Hermes;
- relay admin API stays loopback-only;
- Android transport uses WSS through the public reverse proxy;
- only tokenized `/device-artifacts/*` downloads are additionally exposed for APK transfer;
- read-only tools remain fixed and bounded;
- package metadata tools remain launcher-scoped;
- permission changes are revoke-only, exact-target and locally approved;
- mutating/privileged tools are separate typed functions;
- no arbitrary shell, dumpsys arguments, intent, content URI, remote URL or raw filesystem path is exposed.

## Smoke test

After relay and MCP are configured:

1. Reload MCP in Hermes and call `list_devices`.
2. If needed, call `create_pairing_code` and finish pairing in the Android app.
3. Call `device_health` and `list_apps`.
4. Call `app_permissions` for one returned package and verify only that app is inspected.
5. Call `permissions_audit` and check `scanTruncated/resultTruncated` before treating the result as complete.
6. Enable Usage Access and test `app_usage`.
7. Grant a SAF folder and test `list_files` / `analyze_files`.
8. Activate/authorize Shizuku and call `battery_usage`.
9. On a disposable app with a granted dangerous permission, call `revoke_app_permission`, approve the exact package/permission shown on Android and retry the same call.
10. Test `delete_path`, `force_stop_app` or `uninstall_app` on disposable targets, approving the exact Android card and retrying the same call.
11. Stage a disposable APK and test `install_apk` through the same approval/retry flow.

Physical-device end-to-end validation is still required before Shizuku-backed and mutating actions should be treated as production-ready.
