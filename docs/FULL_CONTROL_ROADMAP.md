# TypedDroid full-control roadmap

## Product target

TypedDroid should let an MCP-capable AI agent use an Android phone as broadly as the user permits while requiring only:

- one Android app: `TypedDroid.apk`;
- one agent-side installation: `typeddroid-mcp`.

No VPS, Shizuku, Termux, Tasker or vendor-specific agent is mandatory.

## Architecture rules

- Agent trust and Android privilege are separate axes.
- `SAFE` preserves the typed-capability model.
- `TRUSTED` unlocks the full-control surface, including raw shell.
- Prefer structured tools for common operations; keep shell as the universal escape hatch.
- The phone is the authority for grants, revocation and audit.
- Remote control must not fundamentally depend on agent and phone sharing one Wi-Fi network.
- External projects may be studied or adapted when licenses allow, but required runtime behavior should live in this repository.

## Phase 0 — foundation

- [x] Create a development branch separate from the release candidate.
- [x] Add `SAFE` / `TRUSTED` agent trust model.
- [x] Separate backend authority from agent trust.
- [x] Define embedded ADB and root capability profiles.
- [x] Supersede the old "never raw shell" product decision for trusted mode.
- [x] Persist pairing-scoped agent trust selection in Android UI.
- [x] Route persisted trust mode into authorization for the new full-control MCP tools.

## Phase 1 — owned embedded ADB backend

Goal: privileged access without requiring Shizuku.

- [x] Add repository-owned ADB key generation/storage using Android Keystore.
- [ ] Complete Android 11+ wireless-debugging pairing product flow.
  - [x] TLS 1.3 pairing transport and TLS exporter.
  - [x] repository-owned SPAKE2 shared-secret authentication.
  - [x] AES-GCM pairing channel and ADB RSA/Device GUID PeerInfo exchange.
  - [x] pairing code validation and in-memory protocol E2E tests.
  - [ ] production onboarding that keeps Android's short-lived pairing dialog alive.
- [ ] Discover/connect to the device's local `adbd` endpoint.
- [x] Add an owned shell-UID `app_process` helper with authenticated abstract-local-socket RPC, bootstrapped through ADB.
- [ ] Complete the minimum ADB host protocol required by TypedDroid.
  - [x] CNXN/STLS negotiation and TLS 1.3 secure-connect client.
  - [x] OPEN/OKAY/WRTE/CLSE stream multiplexer.
  - [x] `shell,v2` framing and stdout/stderr/exit parsing.
  - [ ] mDNS discovery and reconnect lifecycle for `_adb-tls-connect._tcp`.
- [ ] Finish shell execution lifecycle: output/packet/socket-time bounds and helper-side draining exist; cancellation still needs wiring.
- [ ] Reimplement current privileged app tools on the embedded ADB backend.
- [ ] Reimplement Batterystats diagnostics on embedded ADB.
- [ ] Keep Shizuku only as a temporary fallback until parity tests pass.
- [ ] Remove Shizuku dependency/provider/UI/recovery code after parity.

Acceptance: a fresh phone with only TypedDroid installed can pair once through Android Wireless Debugging and perform the current privileged tool set without the Shizuku app.

### Embedded ADB implementation notes

- ADB framing, stream multiplexing, shell-v2, RSA key encoding, SPAKE2, pairing wire protocol, AES-GCM and policy are implemented in this repository.
- TypedDroid bundles Conscrypt as its TLS 1.3 provider because Android's system TLS exporter is hidden from ordinary apps. Conscrypt is library code inside the APK, not a second app or service. Implementing a custom TLS 1.3 stack is explicitly out of scope because it would create unnecessary cryptographic risk.
- Android invalidates the short-lived Wireless Debugging pairing endpoint when the pairing dialog is dismissed in the tested flow. Production onboarding must therefore keep Settings alive, for example with split-screen or a foreground-service/notification flow, rather than assuming the user can freely switch apps after reading the code.


## Phase 2 — full trusted tool surface

- [x] `shell.run` with persistent `TRUSTED` authorization and SAFE-mode denial.
- [ ] activity/intent operations.
- [ ] package manager and appops operations.
- [ ] settings read/write.
- [ ] logcat/process/system diagnostics.
- [ ] broader file operations available to the shell UID.
- [ ] capability introspection so agents know what this phone/backend can actually do.

Structured tools remain preferred for common tasks, but they no longer artificially define the maximum authority of a trusted agent.

## Phase 3 — UI perception and control

- [ ] persistent, explicitly enabled UI-control capability.
- [ ] screenshot/frame acquisition for the agent.
- [ ] UI hierarchy where available.
- [ ] tap, long-press, swipe, text input and key events.
- [ ] open app/activity and wait-for-UI primitives.
- [ ] foreground indicator, kill switch and audit trail.
- [ ] investigate OEM behavior and protected/secure surfaces; do not claim bypasses Android does not provide.

The full-control GitHub build may use AccessibilityService when the user explicitly enables it. Play distribution is a separate product/release-policy decision.

## Phase 4 — notifications, clipboard, media and files

- [ ] notification listener: list/open/dismiss/action/reply.
- [ ] clipboard read/write with Android-version-aware limits.
- [ ] media/photo/document discovery through Android APIs.
- [ ] file copy/move/read/write/search in user-granted storage plus shell-accessible areas.
- [ ] upload/download between agent side and phone with bounded transfer policies.

## Phase 5 — simplify the agent side

Goal: make `typeddroid-host` an implementation detail rather than a product component.

- [ ] move local host/transport responsibilities behind `typeddroid-mcp`.
- [ ] one install command for the agent side.
- [ ] adapters contain configuration only; no Hermes/Codex/Claude-specific protocol.
- [ ] expose the same MCP surface to any MCP-capable host.

## Phase 6 — network independence

- [ ] make device transport reconnect across Wi-Fi/mobile-network changes.
- [ ] support LAN/direct connection when reachable.
- [ ] define an optional Internet traversal/relay mode without making a user-managed VPS mandatory.
- [ ] retain end-to-end device identity/authentication independent of transport.

Important: NAT traversal across arbitrary networks cannot be guaranteed by "no server at all". The architecture must keep relay/rendezvous optional and replaceable, not pretend the networking constraint does not exist.

## Phase 7 — root backend (optional)

- [ ] detect user-provided root without requiring it.
- [ ] explicit root capability grant separate from agent trust.
- [ ] root shell backend with prominent local disclosure/revocation.
- [ ] never make root a requirement for the normal product.

## Phase 8 — release hardening

- [ ] physical-device matrix across Android/OEM versions.
- [ ] reboot and Wireless Debugging recovery.
- [ ] Wi-Fi <-> cellular handover tests.
- [ ] long-running background/battery tests.
- [ ] destructive-operation recovery/idempotency tests.
- [ ] threat-model update for trusted shell and UI control.
- [ ] signed/minified/reproducible release verification.

## Immediate implementation order

1. Trust persistence and session binding.
2. Embedded ADB protocol module and tests.
3. Replace current Shizuku operations one by one.
4. Remove Shizuku completely.
5. Add trusted raw shell.
6. Add UI perception/control.
7. Collapse agent-side host into MCP.
8. Harden remote transport and physical E2E.
