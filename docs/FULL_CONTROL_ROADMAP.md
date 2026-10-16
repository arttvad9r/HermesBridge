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
- [ ] Persist per-device/per-agent trust selection in Android UI.
- [ ] Include trust mode in pairing/session authorization.

## Phase 1 — owned embedded ADB backend

Goal: privileged access without requiring Shizuku.

- [ ] Add repository-owned ADB key generation/storage using Android Keystore where practical.
- [ ] Implement Android 11+ wireless-debugging pairing client.
  - TLS pairing transport.
  - SPAKE2 shared-secret authentication.
  - ADB RSA public-key peer information exchange.
  - pairing code validation and clear local setup UX.
- [ ] Discover/connect to the device's local `adbd` endpoint.
- [ ] Implement the minimum ADB host protocol required by TypedDroid.
  - CNXN/AUTH handshake.
  - OPEN/OKAY/WRTE/CLSE streams.
  - `shell,v2` where supported.
- [ ] Implement bounded shell execution with timeout, stdout/stderr limits and cancellation.
- [ ] Reimplement current privileged app tools on the embedded ADB backend.
- [ ] Reimplement Batterystats diagnostics on embedded ADB.
- [ ] Keep Shizuku only as a temporary fallback until parity tests pass.
- [ ] Remove Shizuku dependency/provider/UI/recovery code after parity.

Acceptance: a fresh phone with only TypedDroid installed can pair once through Android Wireless Debugging and perform the current privileged tool set without the Shizuku app.

## Phase 2 — full trusted tool surface

- [ ] `shell.run` with explicit `TRUSTED` authorization.
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
