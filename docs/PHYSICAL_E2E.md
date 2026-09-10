# Physical phone + VPS end-to-end validation

This checklist is intentionally separate from CI. CI can validate protocol, policy, Android compilation and relay behavior, but it cannot prove real Android background execution, OEM process policy, Shizuku reactivation, mobile-network handover or the public TLS path.

Do not mark the physical E2E roadmap items complete until these steps have been run on the target phone and deployed VPS.

## Test rules

- Prefer the repository-owned disposable `e2e-fixture` APK for install/uninstall/force-stop/revoke checks. Build/use instructions and its deliberately narrow permission surface are documented in `E2E_FIXTURE.md`.
- Do not use a banking, authenticator, launcher, messaging or other important app as a mutation target.
- Use a disposable folder for SAF deletion checks.
- Do not expose relay port `8080` publicly; Hermes/admin traffic stays loopback-only.
- Do not disable the Android-side approval policy for the test.
- Record failures and exact Android/OxygenOS version before changing battery/background settings.

## 1. VPS and public TLS

1. Install the current `hermes-bridge-relay` Actions artifact using the bundled installer.
2. Confirm the service is active:

   ```bash
   sudo systemctl status hermes-bridge-relay
   ```

3. Confirm the loopback health endpoint:

   ```bash
   curl --fail http://127.0.0.1:8080/health
   ```

4. Configure the documented Caddy/Nginx TLS route.
5. From a machine outside the VPS, confirm:

   ```bash
   curl --fail https://YOUR_HOST/health
   ```

6. Verify that `https://YOUR_HOST/api/v1/devices` is **not** publicly proxied.
7. Build the Android APK with exactly:

   ```text
   wss://YOUR_HOST/ws/device
   ```

## 2. First pairing

1. Install the current debug/release-test APK on the phone.
2. Start the relay and Hermes MCP adapter.
3. Create a one-time pairing code through Hermes/MCP.
4. Enter it in Hermes Bridge.
5. Confirm the app reaches `Hermes подключён`.
6. Confirm `list_devices` shows this phone as connected.
7. Run `device_health` from Hermes and compare the returned battery/storage values with the phone UI.

Expected: no laptop or same-Wi-Fi requirement is involved in the actual bridge connection.

## 3. Mobile network handover

1. Keep Hermes running on the VPS.
2. Turn Wi-Fi off on the phone so it moves to LTE/5G.
3. Wait for the bridge to reconnect.
4. Run `device_health` again.
5. Turn Wi-Fi back on and repeat.
6. Lock the phone for at least several minutes and repeat the command.

Expected: connection state may briefly show reconnecting, but pairing is retained and commands resume without user pairing action.

Record reconnect latency for both Wi-Fi -> mobile and mobile -> Wi-Fi transitions.

## 4. Read-only capabilities

Run these before privileged/destructive checks:

- `list_apps`;
- `app_permissions` for a known launcher app;
- `permissions_audit`;
- `app_usage` after explicitly granting Usage Access;
- `list_files` and `analyze_files` inside a disposable SAF tree;
- `battery_usage` after Shizuku is ready.

Verify that:

- hidden packages do not suddenly appear through the launcher-scoped tools;
- files outside the selected SAF tree are inaccessible;
- Batterystats returns parsed bounded data, not a raw shell dump;
- read-only tools do not create approval prompts.

## 5. Approval and mutation checks

Build/install the repository-owned `e2e-fixture` and prepare a disposable test directory. The fixture package is `io.github.arttvad9r.hermesbridge.fixture`; for permission revoke tests, grant `android.permission.CAMERA` from the fixture UI first. The fixture never opens the camera.

For each mutating tool, verify this exact sequence:

1. Hermes sends the command.
2. Nothing changes yet; the phone shows a local approval card.
3. Deny once and verify the action does not happen.
4. Request again, approve, then have Hermes retry the exact same command.
5. Verify the action happens exactly once.
6. Change an argument after approval and verify the old approval is rejected.

Exercise:

- `delete_path` on a disposable file;
- `force_stop_app` on `io.github.arttvad9r.hermesbridge.fixture`;
- `revoke_app_permission` for `android.permission.CAMERA` on the fixture package;
- `uninstall_app` on the fixture package;
- `install_apk` using the fixture APK from the dedicated VPS APK staging directory.

Do not use Hermes Bridge's own package as the target; self-protection should reject those attempts before mutation.

## 6. Reboot behavior

1. With the phone paired and Shizuku configured, reboot the phone.
2. Do not open Hermes Bridge manually immediately.
3. Verify the base foreground bridge restores and `device_health` becomes available again.
4. Verify privileged tools remain unavailable until Shizuku is reactivated.
5. Verify the one-time restoration notification appears only because Shizuku had previously reached READY.
6. Open/reactivate Shizuku from the notification/setup card.
7. Verify a privileged test action works again after authorization is restored.

Expected: Shizuku loss after a stock non-root reboot must not destroy the Hermes pairing or base read-only connection.

## 7. Process death and service recovery

On the test device, exercise ordinary OS process recreation (do not use Hermes to force-stop Hermes Bridge itself):

- swipe the UI task away;
- lock/unlock;
- leave the phone idle;
- move between networks.

Verify the foreground bridge remains or restores according to Android/OEM policy and that the UI reflects the actual connection state when reopened.

If OxygenOS kills the service despite the foreground-service model, record the exact battery/background policy needed and add it to the guided setup rather than silently assuming it.

## 8. Audit history

After the previous checks:

1. Open `История Hermes` from the app/foreground notification.
2. Verify command outcomes and approval decisions are present.
3. Verify APK tokens, pairing codes, raw command arguments, file contents and raw error messages are absent.
4. Clear history and verify it stays cleared after reopening the screen.

## 9. Pairing revocation

1. Use `Отвязать телефон` in Hermes Bridge.
2. Verify the relay removes the public-key binding and the foreground connection stops.
3. Verify Hermes can no longer execute `device_health` for that device.
4. Create a fresh one-time pairing code and re-pair without clearing Android app data.

Separately test the localhost-only admin revoke path as an emergency recovery mechanism.

## Result record

For each physical run, record:

| Field | Value |
| --- | --- |
| Phone model | |
| Android/OxygenOS version | |
| Hermes Bridge commit | |
| APK type | debug / release-test |
| Relay commit | |
| Shizuku version/mode | |
| Wi-Fi -> mobile reconnect | |
| Mobile -> Wi-Fi reconnect | |
| Reboot base reconnect | pass/fail |
| Shizuku restoration notification | pass/fail |
| Read-only tools | pass/fail |
| Approval deny/retry/exact-argument behavior | pass/fail |
| Install/uninstall/force-stop | pass/fail |
| SAF delete | pass/fail |
| Audit redaction | pass/fail |
| Pairing revoke/re-pair | pass/fail |
| Battery impact notes | |
| Open defects | |
