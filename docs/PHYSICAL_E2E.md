# Physical phone + VPS end-to-end validation

This checklist is intentionally separate from CI. CI can validate protocol, policy, Android compilation and relay behavior, but it cannot prove real Android background execution, OEM process policy, Shizuku reactivation, mobile-network handover or the public TLS path.

Do not mark the physical E2E roadmap items complete until these steps have been run on the target phone and deployed VPS.

## Test rules

- Prefer the repository-owned disposable `e2e-fixture` APK for install/uninstall/force-stop/revoke and typed tap/swipe checks. Build/use instructions and its deliberately narrow permission/input surface are documented in `E2E_FIXTURE.md`.
- Do not use a banking, authenticator, launcher, messaging or other important app as a mutation or UI-control target.
- Use a disposable folder for SAF deletion checks.
- Do not expose relay port `8080` publicly; Hermes/admin traffic stays loopback-only.
- Do not disable the Android-side approval or UI-control session policy for the test.
- Record failures and exact Android/OxygenOS version before changing battery/background settings.
- Keep USB ADB connected throughout the run when available. Wi-Fi/mobile handover is a bridge-network test; the ADB transport should stay on USB and must not be treated as evidence that the bridge itself reconnected.
- For a release-gate rerun, pin one exact Git commit. Use the fixture and relay artifacts produced by the successful exact-head CI run for that commit. Build the Android APK from that same commit with the real relay WSS URL, because the generic CI debug artifact intentionally uses the default `wss://bridge.invalid/ws/device` endpoint unless the workflow is explicitly configured otherwise.

## 0. Release-gate preflight

Before changing the phone or VPS state:

1. Record the exact commit to validate and confirm the physical-run branch still points to that commit.
2. Confirm the exact-head Android CI run completed successfully.
3. Download the E2E fixture APK and relay distribution from that exact CI run. Do not mix artifacts from older runs or another branch.
4. Build the Hermes Bridge debug APK locally from the exact same commit with the real relay endpoint:

   ```bash
   ./gradlew --no-daemon \
     -PHERMES_BRIDGE_RELAY_WS_URL=wss://YOUR_HOST/ws/device \
     :app:assembleDebug
   ```

   Do not install the generic Actions debug artifact for the live bridge test unless its embedded endpoint has been independently confirmed to be the intended real host.
5. Record the locally built debug APK SHA-256, the exact WSS endpoint used for the build, and the downloaded fixture/relay artifact SHA-256 or GitHub artifact digests.
6. Confirm `adb devices -l` shows the intended physical phone in `device` state over USB. Reject `offline`, `unauthorized` and emulator targets.
7. Start a local logcat capture before installing/restarting Hermes Bridge. Do not persist pairing codes, APK download tokens or other secrets in the result record.

Expected: the physical result is attributable to one exact source commit, one exact successful CI run, one host-specific Android build from that commit, and the fixture/relay artifacts from that run.

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

4. Confirm the relay listener itself is loopback-only (`127.0.0.1:8080`), then configure the documented Caddy/Nginx TLS route.
5. From a machine outside the VPS, confirm:

   ```bash
   curl --fail https://YOUR_HOST/health
   ```

6. Verify that `https://YOUR_HOST/api/v1/devices` is **not** publicly proxied.
7. Install the host-specific Android APK built in preflight with exactly:

   ```text
   wss://YOUR_HOST/ws/device
   ```

## 2. First pairing

1. Install the current debug/release-test APK on the phone.
2. Start the relay and Hermes MCP adapter.
3. Create a one-time pairing code through Hermes/MCP.
4. Enter it in Hermes Bridge. Do not record the pairing code in screenshots, logs or the result file.
5. Confirm the app reaches `Hermes подключён`.
6. Confirm `list_devices` shows this phone as connected.
7. Run `device_health` from Hermes and compare the returned battery/storage values with the phone UI.

Expected: no laptop or same-Wi-Fi requirement is involved in the actual bridge connection. A first-pair identity must not remain trusted on the relay unless the authentication handshake completes.

## 3. Mobile network handover

1. Keep USB ADB connected and Hermes running on the VPS.
2. Turn Wi-Fi off on the phone so the bridge moves to LTE/5G.
3. Wait for the bridge to reconnect.
4. Run `device_health` again through Hermes/relay.
5. Turn Wi-Fi back on and repeat.
6. Lock the phone for at least several minutes and repeat the command.

Expected: connection state may briefly show reconnecting, but pairing is retained and commands resume without user pairing action. USB ADB staying alive does **not** count as bridge reconnection evidence.

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
6. Change a **semantic** argument after approval and verify the old approval is rejected.

Exercise:

- `delete_path` on a disposable file; change the target path for the mismatch check.
- `force_stop_app` on `io.github.arttvad9r.hermesbridge.fixture`; change the target package for the mismatch check.
- `revoke_app_permission` for `android.permission.CAMERA` on the fixture package; change the permission or target package for the mismatch check.
- `uninstall_app` on the fixture package; change `keepData` (or the target package where supported) for the mismatch check.
- `install_apk` using the fixture APK from the dedicated VPS APK staging directory; change `replace` or verified APK content for the mismatch check. Changing only transport artifact ID/token/file name for the **same verified APK identity** is intentionally not a new local approval target.

Do not use Hermes Bridge's own package as the target; self-protection should reject those attempts before mutation.

For `install_apk`, additionally verify the fixture is actually present after success and that an exact retry does not reinstall it a second time. This is the regression check for the physical package-install staging fix.

## 6. Typed UI-control physical smoke (debug only)

This section validates the internal Shizuku tap/swipe prototype, local session gate and revocation behavior without exposing a remote UI-control tool.

1. Install the current Hermes Bridge **debug** APK and the repository `e2e-fixture` APK.
2. Pair Hermes Bridge normally and bring Shizuku to `READY`.
3. Open the fixture, tap `Reset gesture targets`, and use Android Developer Options **Pointer location** or an ADB/UIAutomator hierarchy dump to determine coordinates inside `hermes_fixture_tap_target` and the left/right interior of `hermes_fixture_swipe_target`. Do not copy coordinates from another device.
4. From the visible Hermes Bridge foreground notification, open the local UI-control session screen and select `Включить на 5 минут`.
5. Open the debug-only `Hermes UI Smoke` launcher. It must show `Session: ACTIVE` and Shizuku `READY`; it must not offer a way to create a session itself.
6. Enter the center coordinates of `TAP TARGET` and select `Send typed tap to fixture`. The harness must bring the fixed repository fixture to the foreground before dispatch. After the 1.5-second settle interval, verify `TAP count` increases by exactly one, then press Back to the smoke harness and verify the backend result is `OK`.
7. Enter start/end coordinates inside the seek bar, using a left-to-right path and a duration such as `500` ms. Select `Send typed swipe to fixture`; verify `SWIPE progress` increases substantially, press Back, and verify the backend result is `OK`.
8. Stop UI control from the local session screen. Return to the smoke harness and repeat the same valid tap attempt. The harness must still execute the attempt so this tests the production gate. Expected backend result: `ui_control_session_required`; the newly opened fixture must show no tap-state change.
9. Re-enable the session, then make Shizuku unavailable. Verify the session becomes stopped. Repeat a valid smoke attempt and verify `ui_control_session_required` with no fixture state change.
10. Restore Shizuku and notification visibility, re-enable the session, then block the Hermes Bridge notification channel (or notification permission where applicable). Within the watchdog interval, verify the session becomes stopped; a valid smoke attempt must be rejected with `ui_control_session_required`. Restore notification visibility before continuing.
11. Re-enable the session and let the five-minute monotonic deadline expire. A valid smoke attempt must return `ui_control_session_required` and leave fixture state unchanged.
12. Open `История Hermes` and verify UI-control lifecycle entries show only session `started/stopped` events; coordinates, swipe duration and stop-message details must be absent.
13. Install/inspect the release-test APK separately and verify the `Hermes UI Smoke` launcher is absent; CI also rejects any release manifest that contains `UiControlSmokeActivity`.

Do not use the smoke harness against any app other than the disposable repository fixture during this gate.

## 7. Reboot behavior

1. With the phone paired and Shizuku configured, reboot the phone.
2. Do not open Hermes Bridge manually immediately.
3. Verify the base foreground bridge restores and `device_health` becomes available again.
4. Verify privileged tools remain unavailable until Shizuku is reactivated.
5. Verify the one-time restoration notification appears only because Shizuku had previously reached READY.
6. Verify no MediaProjection or UI-control session is silently restored after reboot.
7. Open/reactivate Shizuku from the notification/setup card.
8. Verify a privileged test action works again after authorization is restored.

Expected: Shizuku loss after a stock non-root reboot must not destroy the Hermes pairing or base read-only connection. This is also the regression check for paired relay resume before the first post-boot authentication.

## 8. Process death and service recovery

On the test device, exercise ordinary OS process recreation (do not use Hermes to force-stop Hermes Bridge itself):

- swipe the UI task away;
- lock/unlock;
- leave the phone idle;
- move between networks.

Verify the foreground bridge remains or restores according to Android/OEM policy and that the UI reflects the actual connection state when reopened. Also verify a local UI-control session never survives process death or silently reappears after service recreation.

If OxygenOS kills the service despite the foreground-service model, record the exact battery/background policy needed and add it to the guided setup rather than silently assuming it.

## 9. Audit history

After the previous checks:

1. Open `История Hermes` from the app/foreground notification.
2. Verify command outcomes, approval decisions and UI-control session lifecycle events are present.
3. Verify APK tokens, pairing codes, raw command arguments, file contents, SAF paths/target names, package/permission target values from approval decisions, UI coordinates, swipe durations and raw error/stop messages are absent.
4. Clear history and verify it stays cleared after reopening the screen.

## 10. Pairing revocation

1. Use `Отвязать телефон` in Hermes Bridge.
2. Verify the relay removes the public-key binding and the foreground connection stops.
3. Verify Hermes can no longer execute `device_health` for that device.
4. Create a fresh one-time pairing code and re-pair without clearing Android app data.

Separately test the localhost-only admin revoke path as an emergency recovery mechanism.

For the first-pair rollback regression, also perform one disposable interrupted pairing: create a new one-time code, start pairing, terminate the socket/app before authentication completes, then confirm the relay did not retain a trusted device record from that incomplete handshake.

## Result record

For each physical run, record:

| Field | Value |
| --- | --- |
| Phone model | |
| Android/OxygenOS version | |
| Hermes Bridge commit | |
| Exact-head CI run | |
| Android debug APK SHA-256 | |
| Embedded relay WSS endpoint | |
| Fixture artifact digest | |
| Relay artifact digest | |
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
| UI session visible + five-minute expiry | pass/fail |
| Typed fixture tap | pass/fail |
| Typed fixture swipe | pass/fail |
| Stop/Shizuku-loss/notification-loss rejection | pass/fail |
| Debug smoke launcher absent from release | pass/fail |
| Audit redaction incl. approval target data + UI session | pass/fail |
| Interrupted first-pair rollback | pass/fail |
| Pairing revoke/re-pair | pass/fail |
| Battery impact notes | |
| Open defects | |
