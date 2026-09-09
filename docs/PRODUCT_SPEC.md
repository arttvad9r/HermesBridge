# Product specification

## Product statement

Hermes Bridge is an Android companion that lets a user securely delegate phone tasks to a Hermes agent without keeping a laptop nearby.

The product is successful when daily use feels like:

> Install the app, pair Hermes, grant capabilities, then ask Hermes to do things.

Technical concepts such as ADB, MCP servers, ports, addresses and Termux must not be part of normal operation.

## Primary user journey

### First setup

1. Install Hermes Bridge APK.
2. Open the app.
3. Tap **Connect Hermes**.
4. Scan a QR code or enter a one-time pairing code produced by the Hermes-side relay.
5. Complete a guided capability wizard.
6. Optional: enable Advanced access through Shizuku.
7. Optional: enable UI control through Accessibility.
8. Land on one status screen showing that Hermes is connected.

### Daily use

The app normally stays closed. The user talks to Hermes:

- "Why is my battery draining?"
- "Find the largest files I can remove."
- "Install this APK."
- "Uninstall application X."
- "Check permissions for application Y."
- "Force-stop application Z."
- "Open the settings for X and change Y."

Read-only tasks run silently. Mutating or high-risk tasks follow the configured approval policy.

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
- enable/disable supported application state;
- delete selected shared files;
- grant/revoke supported permissions through the privileged backend;
- launch/open applications.

### Optional UI automation

- inspect screen/accessibility tree;
- tap;
- swipe;
- input text;
- open app/settings surface.

UI automation is not the primary control plane. Prefer typed Android operations whenever they exist.

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
  - UI/Accessibility access.
- After reboot, if Shizuku is inactive, basic access stays online and the app shows one actionable notification to restore advanced access.
- No fake success: pairing is only shown as connected after authenticated relay registration succeeds.

## Success criteria for first usable release

- APK installs and runs on Android 11+.
- Pairing works over cellular or unrelated Wi-Fi networks.
- Laptop is not part of the runtime path.
- Device can reconnect after network changes.
- Basic read-only health/app/file queries work remotely.
- At least install APK, uninstall and force-stop work through Shizuku.
- Destructive actions can require approval.
- Audit history identifies tool, timestamp, decision and outcome.
