# MCP integration

TypedDroid exposes one canonical, agent-neutral MCP server over local stdio. The same executable works with Codex, Claude Code, OpenCode, Cursor, Hermes Agent, and other MCP hosts.

The MCP process talks only to the local Bridge Host over loopback. It exposes fixed Android tools plus an explicit `run_shell` capability. The phone itself authorizes `run_shell`: SAFE mode denies it, while a pairing explicitly switched to TRUSTED may use it after the owned privileged helper is active. MCP still exposes no arbitrary HTTP client, arbitrary Android-intent transport, or arbitrary APK URL.

## Install

```bash
python3 -m venv ~/.local/share/typeddroid/mcp-venv
~/.local/share/typeddroid/mcp-venv/bin/pip install ./mcp_adapter
```

The primary executable is:

```text
~/.local/bin/typeddroid-mcp
```

The old `typed-droid-mcp` name remains as a compatibility alias only.

Start the local Bridge Host separately with `./host/run-local-host.sh`. By default the adapter uses `http://127.0.0.1:8080`, reads `~/.config/typeddroid/admin-token`, and stages APKs from `~/.local/share/typeddroid/apks`.

## Agent configuration examples

### Codex

Add a local stdio MCP server in `~/.codex/config.toml`:

```toml
[mcp_servers.android_bridge]
command = "/home/USER/.local/bin/typeddroid-mcp"
```

### Claude Code

```bash
claude mcp add --scope user android_bridge -- \
  /home/USER/.local/bin/typeddroid-mcp
```

### OpenCode

```jsonc
{
  "mcp": {
    "servers": {
      "android_bridge": {
        "type": "local",
        "command": [
          "/home/USER/.local/bin/typeddroid-mcp"
        ]
      }
    }
  }
}
```

### Cursor

```json
{
  "mcpServers": {
    "android_bridge": {
      "command": "/home/USER/.local/bin/typeddroid-mcp",
      "args": []
    }
  }
}
```

### Hermes Agent

Hermes is only an adapter/configuration over the same generic MCP server. See `../adapters/hermes/README.md`.

No agent-specific configuration should contain the Bridge Host admin token unless the default local token-file mechanism is intentionally overridden.

## Read-only MCP tools

### `list_devices()`

Lists paired relay devices and whether each is currently connected.

### `pending_approvals(device_id)`

Lists short-lived approval **notifications** emitted by the authenticated Android device for that device ID. Despite the historical function name, this endpoint is not authoritative approval state: an entry can remain visible until its relay TTL expires after the user has already resolved the local Android ticket.

Each notification contains only:

- `deviceId`;
- `approvalId`;
- one allowlisted typed tool name;
- its expected `MUTATING` or `PRIVILEGED` risk class;
- relay-local expiry time.

No package name, permission name, SAF path, APK filename, raw command argument or Android approval display summary is sent to the host through this notification path.

This tool cannot approve, deny or execute anything. There is intentionally no MCP approval tool in the current design: the threat model includes a compromised host, so the relay admin token, agent process or a Telegram bot callback on that host cannot be treated as an approval authority. Approval still happens locally on Android; after approval the agent retries the same typed operation and Android performs its normal exact-argument, expiry and one-use checks.

### `create_pairing_code()`

Creates a short-lived one-time pairing code for the Android app.

### `device_health(device_id)`

Maps only to the Android tool `device.health`. Returns current battery percentage plus memory/storage totals.

### `battery_usage(device_id)`

Maps only to the Android tool `battery.usage`. It accepts no diagnostic or shell arguments.

Android runs exactly:

```text
dumpsys batterystats -c --charged
```

through the owned shell-UID helper. `-c` requests the current statistics in checkin-format output without using the real `--checkin` path. `--charged` scopes the data to since the last charge. Execution time and stdout/stderr sizes are bounded, and Android parses only supported sections rather than returning the raw dump.

Returned data includes, when Android provides it:

- checkin format version;
- battery capacity and estimated drained power in mAh;
- bounded system power-use items;
- bounded top UIDs by estimated mAh;
- bounded top partial wakelocks and package mappings.

This is accumulated Batterystats data, not instantaneous current/wattage. The tool never runs `--reset` and never accepts arbitrary dumpsys arguments.

### `list_apps(device_id)`

Maps only to `apps.list`. Android keeps its larger launcher-visible set locally for package/policy checks, but the remote result returns at most 128 apps so a control-channel result stays below the WebSocket frame ceiling. `count` is the number actually returned, `totalVisibleCount` is the locally observed launcher-visible count before remote projection, and `truncated=true` means the result must not be treated as a complete list. Labels and version names are also length-bounded and control characters are normalized. TypedDroid does not request `QUERY_ALL_PACKAGES`.

### `app_usage(device_id, days=30)`

Maps only to `apps.usage`. `days` must be an integer from 1 to 365 and defaults to 30.

The phone must have Android Usage Access enabled for TypedDroid. Results are intersected with the same launcher-visible set used by `list_apps`, so Usage Access does not expand the agent's package visibility. The remote projection uses the same 128-app/text bounds and `count`/`totalVisibleCount`/`truncated` contract. When truncation is necessary, the most recently used apps are retained first.

### `app_permissions(device_id, package_name)`

Maps only to `apps.permissions`.

The Android side first verifies that `package_name` is already present in the launcher-visible app set. Only then does it read `PackageInfo` permission metadata. Returned fields include requested permissions, current granted state, Android protection classification, permission group and relevant request flags.

`dangerous=true` means Android itself classifies the permission with the `dangerous` base protection level. TypedDroid does not invent a custom risk score or claim that a granted permission is unnecessary.

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

## TRUSTED full-control tools

### `run_shell(device_id, command, timeout_seconds=30)`

Maps only to Android `shell.run`. The adapter validates the command size, NUL exclusion and a 1–300 second timeout before sending it. Android then enforces pairing-scoped trust: SAFE mode returns `policy_denied`; TRUSTED mode may execute through the TypedDroid-owned shell-UID helper.

The helper is bootstrapped through Android Wireless Debugging but is reached afterward through authenticated loopback-only RPC. Output is bounded and returned as `exitCode`, `stdout`, `stderr`, `stdoutTruncated`, and `stderrTruncated`. This is intentionally a broad capability: a user who enables TRUSTED is granting the agent the authority available to Android's shell UID.

The same TRUSTED boundary also exposes structured convenience tools so agents do not need to construct shell strings for common work:

- `device_capabilities(device_id)` → `device.capabilities`;
- `activity_start(device_id, component=None, action=None, data_uri=None)` → `activity.start`;
- `settings_get(...)` / `settings_put(...)` → `settings.get` / `settings.put`;
- `appops_get(...)` / `appops_set(...)` → `appops.get` / `appops.set`;
- `process_list(device_id)` → `process.list`;
- `logcat_read(device_id, lines=100, buffer="main")` → `logcat.read`;
- `list_notifications(device_id, limit=50)` → `notifications.list`;
- `dismiss_notification(...)` / `open_notification(...)` → `notifications.dismiss` / `notifications.open`;
- `notification_action(...)` / `reply_notification(...)` → `notifications.action` / `notifications.reply`;
- `push_file(device_id, local_name, remote_path, ...)` → staged HTTPS data-plane download + `transfer.push`;
- `pull_file(device_id, remote_path, local_name=None, overwrite=false)` → `transfer.pull` + a one-use HTTPS upload slot;
- `fs_stat` / `fs_list` / `fs_read` / `fs_search` for shell-visible absolute paths;
- `fs_write` / `fs_mkdir` / `fs_copy` / `fs_move` / `fs_delete` for trusted mutable roots (`/sdcard`, `/storage/emulated/0`, `/data/local/tmp`);
- `open_app(...)` → `apps.open`;
- `ui_hierarchy(...)` / `ui_screenshot(...)`;
- `ui_tap(...)` / `ui_swipe(...)` / `ui_long_press(...)`;
- `ui_wait_for_text(...)`;
- `ui_text(...)` / `ui_key(...)`.

Notification tools require Android Notification Access in addition to TRUSTED pairing state. `notifications.list` returns only bounded structured fields and marks the entire payload with `untrustedContent=true`: notification title/text/action labels originate from external apps and must be treated as data, never instructions. The exposed `notificationKey` is an opaque SHA-256 token, not the app-controlled raw Android notification key. Open/action/reply/dismiss are separate explicit calls and use replay protection so retrying the same idempotency key does not execute the PendingIntent twice. TypedDroid does not bypass lockscreen/authentication requirements declared by Android notification actions.

Filesystem inspection/edit tools keep their 128 KiB read and 32 KiB write call bounds. Bulk `push_file`/`pull_file` uses a separate raw HTTP/binary data plane: bytes are never placed in the 256 KiB WebSocket control frame or base64-encoded into command JSON. A transfer is capped at 512 MiB, verified by size and SHA-256, and uses short-lived random bearer capabilities. The device-upload slot is one-use; staged host-to-device download artifacts are short-lived and token-gated for retryability. Typed destructive file operations refuse to delete or move the mutable roots themselves.

Agent-side bulk transfers are confined to `TYPED_DROID_TRANSFER_DIR` (default `~/.local/share/typeddroid/transfers`) and accept only simple direct-child filenames. `push_file` cannot read arbitrary host paths, and `pull_file` cannot write outside that directory or replace an existing local file unless `overwrite=true` is explicit. Android push destinations remain limited to `/sdcard`, `/storage/emulated/0`, and `/data/local/tmp`; pull sources remain limited by what Android's shell UID can actually read.

These tools validate names, paths, bounds and enums on both MCP/Android boundaries and then use the same owned shell-UID helper where applicable. Notification controls use Android's `NotificationListenerService`/`PendingIntent`/`RemoteInput` APIs rather than shell parsing. They are convenience and audit surfaces, not a reduction of the authority already granted by `run_shell`. `appops_set` also protects the TypedDroid package itself.

## Mutating / privileged MCP tools

### `revoke_app_permission(device_id, package_name, permission_name)`

Maps only to the Android tool `apps.revokePermission`.

This is deliberately a revoke-only tool. It cannot grant permissions, revoke all permissions, choose an Android user, add `pm` flags or run another package-manager command.

Before approval Android verifies that:

- `package_name` is launcher-visible;
- the app currently requests `permission_name`;
- Android classifies that permission with the `dangerous` base protection level;
- the permission is currently granted;
- the target is not TypedDroid itself.

The Android user ID is derived locally from the bridge process UID; the agent cannot supply it. Approval is bound to canonical `packageName + permissionName + userId` and is one-use. After approval the agent retries the same logical call. The owned helper receives only the internally built command:

```text
pm revoke --user <derived-user-id> <package> <permission>
```

If the permission is already revoked when the command is retried, the tool returns idempotent success without issuing another privileged command.

### `delete_path(device_id, path_segments)`

Maps only to `files.delete`. The target must be below the SAF root; the granted root itself cannot be deleted. Approval is bound to normalized path plus current target metadata, so a changed target requires a new approval.

### `install_apk(device_id, apk_name, replace=true)`

Maps only to `apps.install` and requires the owned privileged helper plus explicit Android-side approval.

`apk_name` must be a simple `.apk` filename directly inside `TYPED_DROID_APK_DIR`; arbitrary host paths and URLs are rejected.

The flow is:

1. MCP streams the selected APK over the loopback admin API to relay staging.
2. Relay enforces the size limit, computes SHA-256 and creates a short-lived random download token.
3. Android derives the artifact URL only from its configured relay origin and downloads over HTTPS without redirects.
4. Android verifies exact size and SHA-256, then parses package/version/signing certificates.
5. Approval is bound to verified content/package/signing metadata plus `replace`.
6. After approval the agent retries the same logical install.
7. The owned helper receives the declared byte count as a raw local-socket stream, hashes it while writing to a helper-generated `/data/local/tmp` staging path, verifies size/SHA-256, then executes only an internally constructed `pm install [-r] <staging-path>` command. The staging path is not agent-controlled and is deleted after the attempt.

TypedDroid refuses to replace itself through this tool.

### `uninstall_app(device_id, package_name, keep_data=false)`

Maps only to `apps.uninstall`. It requires the owned privileged helper and a one-use Android-side approval bound to normalized `packageName + keepData`. TypedDroid cannot uninstall itself.

### `force_stop_app(device_id, package_name)`

Maps only to `apps.forceStop`. It requires the owned privileged helper and exact-argument approval. TypedDroid cannot force-stop itself because that would terminate the active bridge process.

## Approval behavior

Read-only tools execute without approval but remain fixed, validated and bounded. Mutating/privileged tools do not execute while approval is pending.

Approval fingerprints bind:

```text
tool name + canonical normalized arguments
```

Additionally, APK install approval binds verified content/package/signing metadata, SAF deletion binds current target metadata, and permission revoke binds package + permission + derived Android user. Approved tickets expire and are one-use only.

Android may emit a best-effort `approval.request` notification after creating a local ticket. The relay validates the authenticated device, exact allowlisted tool/risk pair and a relative TTL, then keeps only target-free metadata in bounded memory. `pending_approvals` exposes those notification records read-only to the connected agent. Loss, duplication, staleness or forgery of a relay notification cannot approve an Android ticket and does not alter the local state machine.

When pairing identity is revoked, missing or invalidated for repair, Android clears both pending local approvals and unsent approval notifications before a future re-pair can occur.

Fully remote approval is not implemented. It would require a separate user-controlled signing mechanism independently verifiable by Android; a credential available to the same host is insufficient under the compromised-host threat model.

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
approve_operation(approval_id)
```

Each public MCP function hardcodes one Android tool name. Android applies another typed router/registry and risk policy before execution.

Keep these properties:

- MCP runs locally beside the agent;
- relay admin API stays loopback-only;
- Android transport uses WSS through the public reverse proxy;
- tokenized `/device-artifacts/*` downloads are exposed for APK transfer;
- tokenized `/file-artifacts/*` downloads and one-use `/device-file-uploads/*` slots form the bounded bulk-file data plane; admin-only endpoints create/retrieve those short-lived transfer capabilities;
- approval notifications contain no target arguments or local display summary and cannot resolve tickets;
- read-only tools remain fixed and bounded;
- package metadata tools remain launcher-scoped;
- permission changes are revoke-only, exact-target and locally approved;
- mutating/privileged tools are separate typed functions;
- no arbitrary shell, dumpsys arguments, intent, content URI, remote URL or raw filesystem path is exposed.

## Smoke test

After Bridge Host and MCP are configured:

1. Reload MCP in the chosen agent and call `list_devices`.
2. If needed, call `create_pairing_code` and finish pairing in the Android app.
3. Call `device_health` and `list_apps`; if `truncated=true`, treat the returned list as a bounded projection rather than the full launcher-visible set.
4. Call `app_permissions` for one returned package and verify only that app is inspected.
5. Call `permissions_audit` and check `scanTruncated/resultTruncated` before treating the result as complete.
6. Enable Usage Access and test `app_usage`; check `truncated` before treating its app list as complete.
7. Grant a SAF folder and test `list_files` / `analyze_files`.
8. Enable/restore **Встроенный ADB** and call `battery_usage`.
9. Trigger one mutating command and verify `pending_approvals` exposes only target-free notification metadata while the Android card shows the exact target locally.
10. On a disposable app with a granted dangerous permission, call `revoke_app_permission`, approve the exact package/permission shown on Android and retry the same call.
11. Test `delete_path`, `force_stop_app` or `uninstall_app` on disposable targets, approving the exact Android card and retrying the same call.
12. Stage a disposable APK and test `install_apk` through the same approval/retry flow.

Physical-device end-to-end validation is still required before the owned helper and mutating actions should be treated as production-ready.
## Trusted UI control

When the paired agent is explicitly set to `TRUSTED` and the embedded privileged helper is active, TypedDroid exposes structured UI tools in addition to `shell.run`:

- `ui.hierarchy` — bounded current hierarchy from Android `uiautomator`;
- `ui.screenshot` — bounded JPEG screenshot (currently <= 128 KiB);
- `ui.tap` / `ui.swipe` — bounded screen-coordinate input;
- `ui.text` / `ui.key` — focused text and key-event input;
- `apps.open` — launch a package's normal launcher activity.

These operations are denied in `SAFE`. They are typed helper operations rather than agent-composed shell commands, and all remote command results still pass through the shared audit and response-size boundaries. Secure/protected Android surfaces may still hide or reject content; TypedDroid does not claim to bypass those platform controls.
