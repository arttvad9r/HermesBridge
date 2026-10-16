# ADR 0005: Full-control product surface with an owned privileged backend

Status: Accepted

## Context

TypedDroid started as a way to give an AI agent broad control over a personal Android device. During release-candidate hardening the product was intentionally narrowed to a small set of typed operations backed by Shizuku. That security boundary is useful, but making it the only mode removes much of the original product value and leaves TypedDroid close to a restricted ADB wrapper.

The project also currently requires the external Shizuku app for privileged operations. The target product should require only the TypedDroid APK on Android and an MCP-side component for the agent.

## Decision

TypedDroid will support two independent dimensions:

1. **Agent trust**
   - `SAFE`: typed capabilities only; generic shell/intents/content access are unavailable.
   - `TRUSTED`: full-control capabilities may be exposed, including raw shell and UI control, subject to local policy and audit.
2. **Device authority**
   - standard Android APIs;
   - an owned embedded ADB backend (target privileged backend);
   - optional root backend when the user already has root.

Agent trust and device authority must not be conflated. A trusted agent may run against a device with no privileged backend; a device with embedded ADB may still be exposed to an agent in SAFE mode.

The long-term standard installation is:

```text
MCP-capable agent
      |
      v
typeddroid-mcp
      |
      | authenticated transport
      v
TypedDroid Android APK
      |
      +-- Android APIs
      +-- UI-control backend
      +-- embedded ADB backend
      +-- optional root backend
```

Shizuku is a migration backend only. New privileged architecture must not depend on the Shizuku application or API.

The embedded ADB implementation will be owned in this repository. It may adapt Apache-2.0 AOSP implementation details with attribution, but TypedDroid must not require LADB, Shizuku, Termux, Tasker, or another Android application at runtime.

The protocol implementation remains repository-owned, but TLS is a deliberate exception to the "own the code" preference: TypedDroid bundles Conscrypt inside the APK for TLS 1.3 and TLS-exporter support. A probe on an ordinary Android app showed that the platform Conscrypt exporter is not part of the public app API. Reimplementing TLS 1.3 in application code would add security risk without product value. Conscrypt is therefore an internal library dependency, not an external runtime application or service.

Initial Wireless Debugging pairing also has an Android lifecycle constraint: the temporary pairing endpoint may disappear when the system pairing dialog is dismissed. The onboarding flow must keep that dialog alive while TypedDroid performs pairing, using a supported foreground/split-screen/notification-assisted flow where necessary. This is a setup constraint only; normal post-pairing agent control does not depend on keeping Settings open.

## Full-control boundary

`TRUSTED` may expose generic tools that `SAFE` denies, including raw shell. This does not mean "everything executes silently": approval policy, per-agent permissions, audit, output bounds, timeouts, and sensitive-operation restrictions remain separate controls.

PIN/password/biometric credential collection or remote credential entry remains outside the normal tool surface.

## Distribution consequence

Full UI automation may require Android facilities whose use is restricted by Play policy. GitHub/sideload distribution is therefore the primary target for the full-control build unless a future Play-compatible surface is deliberately separated.

## Migration

ADR 0002 (no raw shell) is superseded for `TRUSTED` mode but remains the policy for `SAFE` mode.

ADR 0003 (no autonomous UI control in the standard build) is superseded by this ADR for the full-control sideload product. Its privacy, visibility and audit requirements remain useful constraints.
