# Permissions and setup

The setup wizard presents capabilities in user terms. Android permission details stay secondary.

## Base connection

Expected Android permissions:

- `INTERNET` — normal permission;
- `ACCESS_NETWORK_STATE` — normal permission;
- notifications later when a foreground connection service requires user-visible status on supported Android versions.

No privileged permission is needed just to pair and maintain an authenticated outbound connection.

## Device diagnostics

Battery, memory and storage totals can be read from standard Android APIs without special user-granted privileged access. The bootstrap app already uses this for its local health card.

## Usage Access

Detailed per-app usage requires Android's special Usage Access (`PACKAGE_USAGE_STATS`) workflow.

This is not a normal runtime permission dialog. The wizard should:

1. explain what Hermes gains;
2. open the system Usage Access settings;
3. return and verify effective access;
4. allow skipping it.

## Files

V1 should prefer scoped/shared storage APIs and user-visible files.

Do not request broad storage access pre-emptively. Add Storage Access Framework / MediaStore flows based on concrete use cases.

Privileged access must not be treated as permission to bypass Android app-private sandbox expectations.

## Advanced access: Shizuku

Shizuku is optional.

Purpose:

- silent/privileged package operations where supported;
- force-stop;
- selected permission/settings operations;
- selected diagnostic `dumpsys` operations through typed tools.

User-facing wizard:

1. Explain Advanced access.
2. Verify Shizuku is installed/available.
3. Guide the user through Wireless Debugging/Shizuku activation.
4. Request Hermes Bridge authorization in Shizuku.
5. Verify binder availability.
6. Mark Advanced access ready.

Without root, Shizuku generally needs service reactivation after device reboot. Hermes Bridge should detect this and keep base capabilities available.

## UI control: Accessibility

Accessibility is a separate optional capability.

The user must explicitly enable the service in Android settings. Hermes Bridge must not attempt to disguise or silently enable it.

UI control should be used only when a typed Android operation does not exist or when the user explicitly wants interaction with an app UI.

## Capability matrix

| Capability | Base | Usage Access | Shizuku | Accessibility |
| --- | :---: | :---: | :---: | :---: |
| Bridge connection | ✓ | | | |
| Battery/memory/storage | ✓ | | | |
| Basic installed app info | ✓ | | | |
| Detailed app usage | | ✓ | | |
| Privileged install/uninstall | | | ✓ | |
| Force-stop | | | ✓ | |
| Selected permission/settings changes | | | ✓ | |
| Tap/swipe/input | | | | ✓ |
