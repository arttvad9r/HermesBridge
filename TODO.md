# Roadmap / TODO

## P0 — bootstrap

- [x] Product specification.
- [x] Architecture and threat model.
- [x] Outbound relay protocol draft.
- [x] Permission/capability model.
- [x] Compose Android app shell.
- [x] Real local battery/memory/storage snapshot.
- [x] Pairing-code validation.
- [x] Default-deny risk/approval policy.
- [x] Unit tests for pairing and approval policy.
- [x] Green CI: unit tests + lint + debug APK.
- [ ] Add and validate the Gradle Wrapper.
- [ ] Add basic app icon/assets without generated artwork.
- [ ] Upgrade Android toolchain only when there is a concrete compatibility reason.

## P0 — real remote connection

- [x] Protocol models with kotlinx.serialization.
- [x] Generate P-256 device key in Android Keystore.
- [x] Short-lived pairing request.
- [x] Challenge/response reconnect authentication.
- [x] Outbound WebSocket client.
- [x] Reconnect with exponential backoff + jitter.
- [x] Foreground connection service and notification.
- [x] Reconnect after reboot/package replacement for already paired devices.
- [x] Relay service beside Hermes.
- [x] VPS deployment examples for systemd + TLS reverse proxy.
- [x] Recover cleanly when relay state no longer recognizes a paired device.
- [x] Admin-only persistent device revocation endpoint.
- [x] Authenticated device self-revocation and local clean re-pair flow.
- [ ] End-to-end test over unrelated networks/mobile data on the physical phone.

## P0 — Hermes MCP boundary

- [x] Local stdio MCP adapter for Hermes.
- [x] Fixed typed tools instead of a generic command passthrough.
- [x] Loopback/HTTPS relay URL validation.
- [x] Admin token validation.
- [x] CI tests for MCP tool mapping and URL hardening.
- [x] `list_devices`.
- [x] `create_pairing_code`.
- [x] `device_health`.
- [x] `battery_usage` with bounded parsed Batterystats power use and top partial wakelocks; no raw shell arguments.
- [x] `list_apps`.
- [x] `app_usage` with optional 1–365 day window, launcher-only visibility and Android Usage Access gate.
- [x] `app_permissions` for one launcher-visible package without broad package visibility.
- [x] `permissions_audit` with bounded launcher-only scan of granted Android-dangerous permissions.
- [x] `revoke_app_permission` for one currently granted Android-dangerous runtime permission behind exact local approval.
- [x] `list_files` inside the user-granted SAF tree.
- [x] `analyze_files` with bounded recursive SAF storage analysis.
- [x] `delete_path` behind Android-side exact-target approval.
- [x] Typed `install_apk` with dedicated VPS staging directory, relay artifact transfer and Android-side verified-content approval.
- [x] Typed `uninstall_app` behind Android-side exact-argument approval.
- [x] Typed `force_stop_app` behind Android-side exact-argument approval.

## P0 — tool boundary

- [x] Explicit Hermes Bridge Android tool allowlist/router.
- [x] `device.health`.
- [x] `battery.usage` through fixed read-only `dumpsys batterystats -c --charged`, parsed locally.
- [x] Bounded top partial-wakelock diagnostics from the same Batterystats snapshot.
- [x] `apps.list` with launcher-only package visibility; no `QUERY_ALL_PACKAGES`.
- [x] `apps.usage` intersects UsageStats with launcher-visible packages so special access does not widen package visibility.
- [x] `apps.permissions` reads requested/granted metadata only after a launcher-visible package check.
- [x] `apps.permissionsAudit` scans at most 200 launcher-visible apps and returns only granted Android-`dangerous` permissions with explicit truncation state.
- [x] Permission-audit output bounded below the relay WebSocket frame limit.
- [x] `apps.revokePermission` accepts only one launcher-visible package + one requested/granted Android-`dangerous` permission, derives the Android user on-device and requires exact one-use approval.
- [x] `files.list` limited to a user-selected SAF directory tree.
- [x] `files.analyze` with bounded traversal, total size and largest-file summaries inside the granted tree.
- [x] `files.delete` with root protection and approval bound to target metadata.
- [x] `apps.install` with staged artifact validation, size/SHA-256 verification, Android package/signing inspection, self-protection and approval.
- [x] `apps.uninstall` with strict package validation, self-protection and approval.
- [x] `apps.forceStop` with strict package validation, self-protection and approval.
- [x] Structured result/error envelopes.
- [x] Path-segment validation and bounded file-list size/depth.
- [ ] Add further read-only app details only when a concrete user scenario justifies them.
- [ ] Idempotency where a future mutating operation is safely retryable.
- [ ] Audit log.

## P1 — Android setup wizard

- [x] Pairing UI.
- [x] Confirmed "Отвязать телефон" flow that revokes the server-side pairing before clearing local state.
- [x] File-access step using Storage Access Framework.
- [x] Persisted SAF grant with explicit revoke/change controls.
- [x] Reboot recovery state for the base relay connection.
- [x] Shizuku detection/setup/authorization card and runtime state.
- [x] Usage Access status/setup card with manual Android special-access flow.
- [x] Guided first-run flow: connect Hermes, choose optional capabilities, finish into the normal dashboard.
- [ ] Accessibility setup as an optional separate step.
- [ ] "Advanced access needs restoration" notification.

## P1 — privileged actions

- [x] Shizuku API/provider dependency and typed privileged backend.
- [x] Install APK through verified staged bytes streamed to `pm install` stdin.
- [x] Uninstall app.
- [x] Force-stop app.
- [x] Revoke one already-granted Android `dangerous` runtime permission from a launcher-visible app after exact local approval.
- [ ] Permission grant operations, only if a separate safe exact-target policy is justified.
- [ ] Selected safe settings operations.
- [x] First selected diagnostic: bounded read-only Batterystats power-use and partial-wakelock snapshot.
- [ ] Additional selected diagnostics (`dumpsys`) wrapped in typed tools as justified.
- [x] Never register upstream/raw shell in the Hermes tool surface.

## P1 — approvals

- [x] Pending approval model.
- [x] Local Android approval UI.
- [x] Approval expiry.
- [x] Approval binds to exact canonical normalized arguments.
- [x] Install approval binds to verified APK content/package/signing metadata rather than ephemeral transfer tokens.
- [x] File deletion approval binds to validated path plus current target metadata.
- [x] Permission-revoke approval binds to package + permission + derived Android user ID.
- [x] Approved tickets are one-use only.
- [ ] Hermes/Telegram approval routing.
- [ ] Per-tool "always allow" only where explicitly safe.
- [x] Emergency disconnect/revoke button for the Hermes pairing itself.

## P2 — UI automation

- [ ] Accessibility service.
- [ ] Screen structure/screenshot strategy.
- [ ] Tap/swipe/input typed actions.
- [ ] Short-lived UI-control sessions.
- [ ] Sensitive-field redaction where feasible.

## P2 — hardening/release

- [ ] Dependency/advisory audit.
- [ ] Network security config and TLS validation tests.
- [ ] Keystore migration/recovery tests.
- [ ] Process-death/reboot/network-handover tests on device.
- [ ] SAF permission revocation/provider failure tests on device.
- [ ] Usage Access behavior/revocation tests on physical device.
- [ ] Shizuku reboot/reactivation tests on physical device.
- [ ] Physical-device install/uninstall/force-stop/file-delete/battery-diagnostics/app-usage/app-permissions/permissions-audit/permission-revoke E2E through the deployed VPS relay.
- [ ] Battery impact measurements.
- [ ] R8/release build and reflection keep-rule verification.
- [ ] Signed reproducible release process.
- [ ] Decide project license.
