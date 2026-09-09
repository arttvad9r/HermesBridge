# Permissions and setup

Hermes Bridge should expose capabilities in user terms first. Android permission mechanics stay secondary and are requested only when a concrete feature needs them.

## Base connection

Current Android permissions:

- `INTERNET` — outbound relay connection;
- `ACCESS_NETWORK_STATE` — connection state;
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` — long-lived authenticated bridge connection;
- `RECEIVE_BOOT_COMPLETED` — restore an already paired bridge after reboot.

The foreground service remains user-visible through its ongoing notification. Hermes Bridge does not request `POST_NOTIFICATIONS` solely to make the foreground service function.

No privileged permission is needed just to pair and maintain the authenticated outbound connection.

## Device diagnostics

Battery, memory and storage totals use standard Android APIs and require no privileged user grant.

Current tool:

- `device.health`

## App visibility

Android 11+ filters package visibility by default.

Hermes Bridge deliberately does **not** request `QUERY_ALL_PACKAGES`. The manifest declares only a `MAIN` + `LAUNCHER` `<queries>` signature, and `apps.list` reports launcher-visible applications that match that scope.

This keeps the initial app inventory useful without granting blanket visibility into every installed package.

Current tool:

- `apps.list`

Future privileged package operations should use explicit package arguments and the narrowest visibility model that still supports them.

## Files

Hermes Bridge uses Android's Storage Access Framework (`ACTION_OPEN_DOCUMENT_TREE`) rather than broad storage permissions.

Flow:

1. The user taps **Choose folder** in Hermes Bridge.
2. Android shows the system document-tree picker.
3. The user selects one allowed directory tree.
4. Hermes Bridge persists only the read grant for that tree.
5. `files.list` can browse that directory and its descendants.
6. The user can change the selected tree or revoke Hermes Bridge's stored grant from the app UI.

The tool receives a JSON array of path segments instead of an arbitrary path string. The Android side validates depth, segment length, traversal markers and separators before resolving the directory.

Current restrictions:

- read-only listing only;
- no delete, move, rename or write operations;
- no `MANAGE_EXTERNAL_STORAGE`;
- no raw filesystem path passthrough;
- no access outside the user-selected SAF tree;
- Android's own SAF restrictions still apply, including protected locations that cannot be granted through the picker on modern Android versions.

Current tool:

- `files.list`

## Usage Access

Detailed per-app usage requires Android's special Usage Access (`PACKAGE_USAGE_STATS`) workflow.

This is not a normal runtime permission dialog. A later setup step should:

1. explain what Hermes gains;
2. open the system Usage Access settings;
3. return and verify effective access;
4. allow skipping it.

Usage Access is not currently requested.

## Advanced access: Shizuku

Shizuku is optional and not currently enabled.

Planned purposes:

- install/uninstall where supported;
- force-stop;
- selected permission/settings operations;
- selected diagnostic `dumpsys` operations through typed tools.

Before any of these tools are exposed to Hermes, the project needs a working approval model that binds approval to the exact normalized command arguments.

User-facing setup should eventually:

1. Explain Advanced access.
2. Verify Shizuku is installed/available.
3. Guide the user through Wireless Debugging/Shizuku activation.
4. Request Hermes Bridge authorization in Shizuku.
5. Verify binder availability.
6. Mark Advanced access ready.

Without root, Shizuku generally needs service reactivation after device reboot. Base Hermes Bridge capabilities must continue working if Shizuku is unavailable.

## UI control: Accessibility

Accessibility is a separate optional capability and is not currently implemented.

The user must explicitly enable the service in Android settings. Hermes Bridge must not attempt to disguise or silently enable it.

UI control should be used only when a typed Android operation does not exist or when the user explicitly wants interaction with an app UI.

## Capability matrix

| Capability | Base | SAF folder | Usage Access | Shizuku | Accessibility |
| --- | :---: | :---: | :---: | :---: | :---: |
| Bridge connection | ✓ | | | | |
| Battery/memory/storage | ✓ | | | | |
| Launcher-visible app list | ✓ | | | | |
| Read files in selected tree | | ✓ | | | |
| Detailed app usage | | | ✓ | | |
| Privileged install/uninstall | | | | ✓ | |
| Force-stop | | | | ✓ | |
| Selected permission/settings changes | | | | ✓ | |
| Tap/swipe/input | | | | | ✓ |
