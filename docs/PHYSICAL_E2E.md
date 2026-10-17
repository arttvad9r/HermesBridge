# Physical phone + managed MCP runtime end-to-end validation

This is the release gate for the exact release-candidate build. Do not mark physical-device items complete from unit tests alone.

## 1. Host and device ingress

1. Install/configure `typeddroid-mcp` as the normal desktop user; its internal relay starts automatically:

   ```bash
   ./host/run-local-host.sh
   ```

2. Confirm `http://127.0.0.1:8080/health` returns `ok`.
3. Confirm the process listens only on loopback for its admin API.
4. Configure a trusted `wss://HOST[:PORT]/ws/device` device ingress that forwards only `/health`, `/ws/device`, and `/device-artifacts/*`.
5. Verify `/api/v1/*` is not reachable through the device-facing endpoint.
6. Build the exact Android RC with that canonical WSS endpoint.

## 2. Generic MCP adapter

Install `mcp_adapter/` into a clean Python environment and connect it from at least two different MCP hosts where practical (for example Codex plus OpenCode/Claude Code).

Required checks:

- `list_devices` works identically across hosts;
- `create_pairing_code` creates one short-lived pairing code;
- SAFE mode rejects `shell.run`; TRUSTED mode exposes it only after an explicit local trust grant and embedded-helper bootstrap;
- internal relay admin token is read locally and is not copied into agent project configuration.

## 3. Pairing and reconnect

On the physical phone:

1. Install the exact RC APK.
2. Pair with a one-time code.
3. Verify Android reaches `CONNECTED` only after authenticated challenge/response.
4. Kill/restart the app process and verify automatic reconnect.
5. Reboot the phone and verify base connection recovery.
6. Restart the MCP runtime while preserving its state directory and verify reconnect without re-pairing.
7. Remove internal relay pairing state intentionally and verify Android handles `unknown_device` cleanly and can re-pair.

## 4. Network handover

With the internal relay/runtime unchanged, move the phone through the relevant network paths:

- Wi-Fi → mobile data;
- mobile data → Wi-Fi;
- temporary loss of connectivity;
- device-ingress address remaining valid while routes change.

Confirm reconnect uses backoff/jitter and no command runs before authentication.

## 5. Android permission/capability recovery

Validate on the physical phone:

- notification permission grant, deny, and settings recovery;
- SAF grant, revoke, provider failure, and changed tree;
- Usage Access grant and revoke;
- embedded-ADB pairing, helper bootstrap, reboot/process-loss restoration, and key revocation;
- local audit persistence and clear-history behavior.

## 6. Typed tool matrix

Run through the generic MCP adapter and verify exact Android results for:

Read-only:

- `device_health`
- `battery_usage`
- `list_apps`
- `app_usage`
- `app_permissions`
- `permissions_audit`
- `list_files`
- `analyze_files`

Mutating/privileged:

- `revoke_app_permission`
- `delete_path`
- `install_apk` using an APK from the local staging directory
- `uninstall_app`
- `force_stop_app`

For every mutating tool verify:

1. first call creates an Android-local approval;
2. changed arguments cannot consume the old approval;
3. denial is final for that ticket;
4. approval is one-use;
5. keyed exact retries are idempotent;
6. reuse of the same idempotency key with different arguments fails closed.

## 7. Owned privileged helper physical gate

Confirm the release build bootstraps `EmbeddedPrivilegedMain` from the installed APK, authenticates the app/helper loopback RPC with the per-bootstrap secret, and performs install/uninstall/force-stop/permission-revoke/Batterystats without any companion privilege app. Verify `shell.run` is denied in SAFE and works in TRUSTED.

Test closing Wireless Debugging after bootstrap, helper process death, reboot restoration with the persisted ADB identity, Android-side key revocation, and restoration failure without silently widening authority.


## 8. Battery impact

Measure an idle baseline and a representative connected period with the foreground connection active. Record Android battery usage and wake behavior. The release gate should fail if the bridge causes material unexplained idle drain.

## 9. Release build

Repeat the physical smoke test with the minified signed RC, not only debug builds. Confirm:

- `EmbeddedPrivilegedMain` survives R8 under its exact bootstrap class name;
- pairing/reconnect still works;
- all tool schemas match debug behavior;
- no debug-only test surface is present.
