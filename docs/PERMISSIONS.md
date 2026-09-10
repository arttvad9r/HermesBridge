# Permissions and setup

Hermes Bridge exposes capabilities in user terms first. Android permission mechanics stay secondary and are requested only when a concrete feature needs them.

## Base connection

Current Android permissions:

- `INTERNET` — outbound relay connection;
- `ACCESS_NETWORK_STATE` — connection state;
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` — long-lived authenticated bridge connection;
- `RECEIVE_BOOT_COMPLETED` — restore an already paired bridge after reboot;
- `POST_NOTIFICATIONS` — Android 13+ notification-drawer UX for connection status, quick history access and Shizuku restoration reminders.

`POST_NOTIFICATIONS` is not authority to control the phone and is not required for the authenticated relay itself to work. On Android 13+ Hermes Bridge shows an explicit setup card and requests it only after the user presses the button. If the user denies it, the base foreground service/relay can continue, but ordinary notification-drawer visibility and the Shizuku reboot reminder are unavailable.

No privileged permission is needed just to pair and maintain the authenticated outbound connection.

## Device diagnostics

Battery, memory and storage totals use standard Android APIs and require no privileged user grant.

Current tool:

- `device.health`

The richer `battery.usage` diagnostic is optional and uses the Shizuku backend through one fixed read-only Batterystats command. Hermes cannot pass arbitrary shell or `dumpsys` arguments.

## App visibility

Android 11+ filters package visibility by default.

Hermes Bridge deliberately does **not** request `QUERY_ALL_PACKAGES`. The manifest declares:

- a `MAIN` + `LAUNCHER` query used to derive the normal app inventory;
- one exact package query for the official Shizuku package (`moe.shizuku.privileged.api`) so the recovery UI can offer a direct **Открыть Shizuku** action when it is installed.

`apps.list`, usage data, permission reads, permission audit and permission revocation remain launcher-scoped. The exact Shizuku package query does not become part of the agent's general app inventory.

Current app tools include:

- `apps.list`;
- `apps.usage`;
- `apps.permissions`;
- `apps.permissionsAudit`;
- `apps.revokePermission`.

## Files

Hermes Bridge uses Android's Storage Access Framework (`ACTION_OPEN_DOCUMENT_TREE`) rather than broad storage permissions.

Flow:

1. The user taps the folder-selection action in Hermes Bridge.
2. Android shows the system document-tree picker.
3. The user selects one allowed directory tree.
4. Hermes Bridge persists the grant for that tree.
5. `files.list` and `files.analyze` operate only inside that tree.
6. The user can change the selected tree or revoke Hermes Bridge's stored grant from the app UI.
7. `files.delete` may delete one validated descendant only after an exact local approval. The granted root itself is protected.

Tool paths are logical arrays of validated path segments, not arbitrary filesystem paths. Traversal markers, separators, excessive depth and oversized segments are rejected before resolution.

Current restrictions:

- no `MANAGE_EXTERNAL_STORAGE`;
- no raw filesystem path passthrough;
- no access outside the user-selected SAF tree;
- no generic move/rename/write API;
- deletion is a separate typed action with exact target validation and approval;
- Android's own SAF restrictions still apply, including protected locations that cannot be granted through the picker on modern Android versions.

Current tools:

- `files.list`;
- `files.analyze`;
- `files.delete`.

## Usage Access

Detailed per-app usage uses Android's special Usage Access (`PACKAGE_USAGE_STATS`) workflow.

This is not a normal runtime permission dialog. Hermes Bridge currently provides an optional setup/status card that:

1. explains what the capability adds;
2. opens the system Usage Access settings;
3. lets the user return and re-check effective access;
4. allows the user to leave it unconfigured.

`apps.usage` intersects UsageStats results with the same launcher-visible package set used by `apps.list`, so granting Usage Access does not widen package visibility for Hermes.

## Advanced access: Shizuku

Shizuku is optional and is the implemented privileged backend. Root is not required.

Current typed uses include:

- APK installation from a verified Hermes Bridge staged artifact;
- uninstall;
- force-stop;
- revoke one already-granted Android `dangerous` runtime permission from one launcher-visible app;
- the fixed read-only Batterystats diagnostic.

Hermes Bridge never registers a generic Shizuku shell for Hermes. Privileged operations use internally constructed commands and the Android-side typed router/approval policy remains authoritative.

### Setup

The app exposes Shizuku state separately from base Hermes connectivity. Once Hermes Bridge successfully reaches Shizuku `READY`, it stores only the fact that advanced access was previously configured. That flag contains no Shizuku credential or command capability.

### After reboot

For a non-root Shizuku setup started through Wireless Debugging, the Shizuku service normally needs to be started again after a device reboot. The Android pairing itself generally does not need to be repeated each time.

Hermes Bridge handles this without treating Shizuku loss as Hermes connection loss:

1. `BOOT_COMPLETED` starts the already-paired base Hermes relay connection immediately.
2. The foreground service waits briefly before checking Shizuku, avoiding an immediate false warning during boot.
3. If Shizuku had previously reached `READY`, is still unavailable, and notification permission is granted, Hermes Bridge shows **Расширенный доступ нужно восстановить**.
4. The notification explains that the base Hermes connection is still active.
5. The notification opens Hermes Bridge and, when the official Shizuku app is visible, includes **Открыть Shizuku**.
6. When Shizuku becomes `READY` again, the restoration notification is cancelled.

`MY_PACKAGE_REPLACED` uses the normal reconnect path and does not generate a fake post-reboot warning.

On ColorOS/OnePlus devices, Shizuku's own troubleshooting guidance may require changing the OEM Developer Options setting commonly named **Permission monitoring** if ADB-mode permissions do not work correctly. This is device-side Shizuku setup, not a permission Hermes Bridge can silently alter.

## Notifications

Notifications are deliberately a UX capability rather than a privilege boundary.

On Android 13+:

- Hermes Bridge declares `POST_NOTIFICATIONS`;
- the user explicitly initiates the runtime request from the setup/dashboard card;
- denial does not stop the relay or remove already granted device capabilities;
- the card explains what will be missing and provides a system notification-settings fallback;
- the Shizuku restoration notifier checks effective permission again immediately before posting.

The restoration notification contains no pairing code, auth token, raw tool argument, file content or other secret-bearing data.

## Future UI-control session

UI control is not currently implemented and the standard build does not declare an autonomous `AccessibilityService` for Hermes. See [ADR 0003](decisions/0003-ui-control-policy.md).

Future UI-control must remain a separate opt-in capability rather than inheriting authority from the base relay or ordinary Shizuku setup. Typed Android/Shizuku operations are preferred for actions. If screen pixels are needed, the user must explicitly start a short-lived MediaProjection session and accept the system capture prompt for each new capture session. The projection runs only with the required `mediaProjection` foreground-service type and is never silently restored after reboot.

A future setup card must appear only after a concrete compliant UI-control implementation exists. Hermes Bridge must not add a placeholder Accessibility permission/service merely to satisfy the wizard roadmap.

## Capability matrix

| Capability | Base | Notifications | SAF folder | Usage Access | Shizuku | Future UI-control session |
| --- | :---: | :---: | :---: | :---: | :---: | :---: |
| Bridge connection | ✓ | | | | | |
| Battery/memory/storage totals | ✓ | | | | | |
| Launcher-visible app list | ✓ | | | | | |
| Notification-drawer status/history | | ✓ | | | | |
| Shizuku reboot reminder | | ✓ | | | ✓ | |
| Read/analyze selected files | | | ✓ | | | |
| Approved delete inside selected tree | | | ✓ | | | |
| Detailed app usage | | | | ✓ | | |
| Batterystats diagnostic | | | | | ✓ | |
| Privileged install/uninstall | | | | | ✓ | |
| Force-stop | | | | | ✓ | |
| Approved dangerous-permission revoke | | | | | ✓ | |
| Screen capture / tap / swipe / input | | | | | | future |

## Physical validation still required

CI covers the permission/recovery policy and project build, but it cannot substitute for device behavior. Before release, test at minimum:

- Android 13+ notification grant, denial and system-settings return behavior;
- actual OnePlus/ColorOS reboot and base relay reconnect;
- Shizuku becoming unavailable after reboot and the restoration notification appearing only when expected;
- reopening Shizuku and automatic cancellation of the reminder;
- the privileged operations themselves through the deployed VPS relay.
