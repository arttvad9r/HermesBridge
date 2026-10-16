# Product specification

## Product statement

TypedDroid is an Android companion that lets a user securely delegate typed phone tasks to any compatible AI agent through a local agent-neutral bridge.

The product is successful when daily use feels like:

> Install the app, pair the phone with Bridge Host, grant capabilities, then use the AI agent normally.

Technical concepts such as ADB, MCP servers, ports, addresses and Termux must not be part of normal operation.

## Primary user journey

### First setup

1. Install TypedDroid APK.
2. Open the app.
3. Tap **Connect Bridge**.
4. Scan a QR code or enter a one-time pairing code produced by the local Bridge Host.
5. Complete a guided capability wizard.
6. Optional: enable Advanced access through Shizuku.
7. Land on one status screen showing that the bridge is connected.

UI control is not part of the standard V1 setup. If a future policy-compatible UI-control backend is enabled, it is a separate explicit capability/session rather than an Accessibility permission bundled into first-run setup.

### Daily use

The app normally stays closed. The user talks to their AI agent:

- "Why is my battery draining?"
- "Find the largest files I can remove."
- "Install this APK."
- "Uninstall application X."
- "Check permissions for application Y."
- "Force-stop application Z."
- "Open the settings for X and change Y."

Read-only tasks run silently. Mutating or high-risk tasks follow the configured approval policy. Requests that require future UI-control support fail deterministically when no explicit UI-control session is available.

## V1 capabilities

### Observe

- battery/device health;
- memory and storage;
- installed applications;
- app metadata and permissions;
- usage statistics where Android Usage Access allows it;
- user-visible/shared files and downloads;
- network state and traffic statistics where available.

### Act

- install APK;
- uninstall application;
- force-stop application;
- delete selected shared files;
- revoke one supported dangerous runtime permission through the privileged backend;

### Future optional UI automation

- inspect explicitly shared screen content;
- tap;
- swipe;
- input text;
- open app/settings surface.

UI automation is not the primary control plane. Prefer typed Android operations whenever they exist. The standard build does not use `AccessibilityService` for autonomous agent actions. Future UI-control work follows [ADR 0003](decisions/0003-ui-control-policy.md): narrow typed operations plus an explicit short-lived user-consented capture/control session where screen pixels are required.

## Non-goals

V1 will not provide:

- arbitrary remote shell;
- root access;
- arbitrary Intent execution;
- arbitrary `content://` access;
- automatic extraction of private app data under `/data/data`;
- silent PIN/password capture;
- lock-screen bypass;
- unrestricted background microphone/camera/location access.

## UX requirements

- One primary status screen after setup.
- Setup is a guided wizard, not a settings checklist.
- Explain capabilities in user language, not Android permission names.
- Every setup step deep-links to the relevant Android settings page when possible.
- Clearly distinguish:
  - connected/disconnected;
  - base access;
  - Usage Access;
  - Advanced/Shizuku access;
  - any future active UI-control session.
- After reboot, if Shizuku is inactive, basic access stays online and the app shows one actionable notification to restore advanced access.
- Screen capture/UI-control must never be silently restored after reboot.
- No fake success: pairing is only shown as connected after authenticated device registration succeeds.

## Success criteria for first usable release

- APK installs and runs on Android 11+.
- Pairing works over cellular or unrelated Wi-Fi networks.
- The user's agent machine runs Bridge Host locally; no separate always-on server is required.
- Device can reconnect after network changes.
- Basic read-only health/app/file queries work through the generic MCP adapter.
- At least install APK, uninstall and force-stop work through Shizuku.
- Destructive actions can require approval.
- Audit history identifies tool, timestamp, decision and outcome.
