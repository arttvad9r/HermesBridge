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
- [ ] Add and validate the Gradle Wrapper after baseline CI is green.
- [ ] Add basic app icon/assets without generated artwork.
- [ ] Upgrade Android toolchain only after baseline CI is green.

## P0 — real remote connection

- [ ] Implement protocol models with kotlinx.serialization.
- [ ] Generate P-256 device key in Android Keystore.
- [ ] Implement short-lived pairing request.
- [ ] Implement challenge/response reconnect authentication.
- [ ] Implement outbound WebSocket client.
- [ ] Reconnect with exponential backoff + jitter.
- [ ] Foreground connection service and notification.
- [ ] Add relay service beside Hermes.
- [ ] End-to-end test over unrelated networks/mobile data.
- [ ] Device revocation and re-pair flow.

## P0 — tool boundary

- [ ] Introduce `droid-mcp` 0.10.1 modules incrementally.
- [ ] Build explicit Hermes Bridge tool allowlist.
- [ ] Device health tool.
- [ ] Installed-app listing/info.
- [ ] File/download listing and size analysis.
- [ ] Strict argument schemas and size/path limits.
- [ ] Structured result/error envelopes.
- [ ] Idempotency for mutating tools.
- [ ] Audit log.

## P1 — Android setup wizard

- [ ] Connection/pairing wizard.
- [ ] Usage Access step.
- [ ] File-access step based on scoped storage requirements.
- [ ] Shizuku detection/setup/authorization step.
- [ ] Accessibility setup as an optional separate step.
- [ ] Reboot recovery state.
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
- [ ] Emergency disconnect/revoke button.

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
- [ ] Process-death/reboot/network-handover tests.
- [ ] Battery impact measurements.
- [ ] R8/release build.
- [ ] Signed reproducible release process.
- [ ] Decide project license.
