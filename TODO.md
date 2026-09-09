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
- [ ] End-to-end test over unrelated networks/mobile data on the physical phone.
- [ ] Explicit device revocation endpoint and local re-pair flow.

## P0 — Hermes MCP boundary

- [x] Local stdio MCP adapter for Hermes.
- [x] Fixed typed tools instead of a generic command passthrough.
- [x] Loopback/HTTPS relay URL validation.
- [x] Admin token validation.
- [x] CI tests for MCP tool mapping and URL hardening.
- [x] `list_devices`.
- [x] `create_pairing_code`.
- [x] `device_health`.
- [x] `list_apps`.
- [x] `list_files` inside the user-granted SAF tree.
- [ ] Package/install MCP tools only after local approvals exist.

## P0 — tool boundary

- [x] Explicit Hermes Bridge Android tool allowlist.
- [x] `device.health`.
- [x] `apps.list` with launcher-only package visibility; no `QUERY_ALL_PACKAGES`.
- [x] `files.list` limited to a user-selected SAF directory tree.
- [x] Structured result/error envelopes.
- [x] Path-segment validation and bounded file-list size/depth.
- [ ] Add read-only app details as needed without broad package visibility.
- [ ] File size analysis/large-file summaries inside the granted tree.
- [ ] Introduce selected `droid-mcp` modules only where they add value behind the same allowlist.
- [ ] Idempotency for future mutating tools.
- [ ] Audit log.

## P1 — Android setup wizard

- [x] Pairing UI.
- [x] File-access step using Storage Access Framework.
- [x] Persisted read-only SAF grant with explicit revoke/change controls.
- [x] Reboot recovery state for the base relay connection.
- [ ] Turn the current cards into a guided first-run setup flow.
- [ ] Usage Access step.
- [ ] Shizuku detection/setup/authorization step.
- [ ] Accessibility setup as an optional separate step.
- [ ] "Advanced access needs restoration" notification.

## P1 — privileged actions

- [ ] Add Shizuku dependency/backend.
- [ ] Install APK.
- [ ] Uninstall app.
- [ ] Force-stop app.
- [ ] Selected permission operations.
- [ ] Selected safe settings operations.
- [ ] Selected diagnostics (`dumpsys`) wrapped in typed tools.
- [ ] Never register upstream raw shell.

## P1 — approvals

- [ ] Pending approval model.
- [ ] Local Android approval UI.
- [ ] Hermes/Telegram approval routing.
- [ ] Approval expiry.
- [ ] Approval binds to exact normalized arguments.
- [ ] Per-tool "always allow" only where explicitly safe.
- [ ] Emergency disconnect/revoke button for the Hermes pairing itself.

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
- [ ] Battery impact measurements.
- [ ] R8/release build.
- [ ] Signed reproducible release process.
- [ ] Decide project license.
