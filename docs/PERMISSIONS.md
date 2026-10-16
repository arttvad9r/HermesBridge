# Permissions and setup

TypedDroid exposes capabilities in user terms first. Android permission mechanics stay secondary and are requested only when a concrete feature needs them.

## Base connection

Current Android permissions:

- `INTERNET` — outbound relay connection;
- `ACCESS_NETWORK_STATE` — connection state;
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` — long-lived authenticated bridge connection;
- `RECEIVE_BOOT_COMPLETED` — restore an already paired bridge after reboot;
- `POST_NOTIFICATIONS` — Android 13+ notification-drawer UX for connection status and quick history access.

`POST_NOTIFICATIONS` is not authority to control the phone and is not required for the authenticated relay itself to work. On Android 13+ TypedDroid shows an explicit setup card and requests it only after the user presses the button. If the user denies it, the base foreground service/relay can continue, but ordinary notification-drawer visibility is unavailable.

No privileged permission is needed just to pair and maintain the authenticated outbound connection.

## Device diagnostics

Battery, memory and storage totals use standard Android APIs and require no privileged user grant.

Current tool:

- `device.health`

The richer `battery.usage` diagnostic is optional and uses the owned helper through one fixed read-only Batterystats command. The agent cannot pass arbitrary shell or `dumpsys` arguments.

## App visibility

Android 11+ filters package visibility by default.

TypedDroid deliberately does **not** request `QUERY_ALL_PACKAGES`. The manifest declares:

- a `MAIN` + `LAUNCHER` query used to derive the normal app inventory;

`apps.list`, usage data, permission reads, permission audit and permission revocation remain launcher-scoped.

Current app tools include:

- `apps.list`;
- `apps.usage`;
- `apps.permissions`;
- `apps.permissionsAudit`;
- `apps.revokePermission`.

## Files

TypedDroid uses Android's Storage Access Framework (`ACTION_OPEN_DOCUMENT_TREE`) rather than broad storage permissions.

Flow:

1. The user taps the folder-selection action in TypedDroid.
2. Android shows the system document-tree picker.
3. The user selects one allowed directory tree.
4. TypedDroid persists the grant for that tree.
5. `files.list` and `files.analyze` operate only inside that tree.
6. The user can change the selected tree or revoke TypedDroid's stored grant from the app UI.
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

This is not a normal runtime permission dialog. TypedDroid currently provides an optional setup/status card that:

1. explains what the capability adds;
2. opens the system Usage Access settings;
3. lets the user return and re-check effective access;
4. allows the user to leave it unconfigured.

`apps.usage` intersects UsageStats results with the same launcher-visible package set used by `apps.list`, so granting Usage Access does not widen package visibility for the agent.

## Advanced access: embedded ADB + owned helper

The target privileged backend does not require a second Android app. TypedDroid owns a persistent RSA ADB identity in Android Keystore and uses Android 11+ Wireless Debugging only to pair and bootstrap a helper process from the installed TypedDroid APK.

The Android UI now provides an **Встроенный ADB** setup card. Initial setup is:

1. enable Developer options and Wireless debugging;
2. open **Pair device with pairing code**;
3. keep that short-lived pairing dialog alive (split-screen with TypedDroid is the current compatible flow on devices that close it when Settings loses foreground);
4. enter the six-digit code in TypedDroid;
5. TypedDroid discovers `_adb-tls-pairing._tcp` and `_adb-tls-connect._tcp` with Android NSD, performs SPAKE2/TLS pairing, and starts its own `app_process` helper as Android shell UID;
6. the app verifies the helper over an authenticated loopback-only endpoint and stores only that local endpoint credential plus the fact that its ADB identity has been paired.

The helper accepts connections only from the exact TypedDroid app UID, while the app verifies that the peer is Android shell UID. No TCP listener is created for helper RPC. Raw shell is still denied to SAFE agent pairings; it is exposed only after the user explicitly changes that agent pairing to TRUSTED.

The helper is designed to remain usable after the Wireless Debugging transport used for bootstrap is closed, until Android kills the helper process or the phone reboots. This lifecycle still requires physical-device/OEM verification before stable release. After reboot, the stored ADB identity can normally bootstrap a new helper without another six-digit code as long as Android has not revoked the paired key; Wireless debugging must temporarily be available again for that restore step.


## Notifications

Notifications are deliberately a UX capability rather than a privilege boundary.

On Android 13+:

- TypedDroid declares `POST_NOTIFICATIONS`;
- the user explicitly initiates the runtime request from the setup/dashboard card;
- denial does not stop the relay or remove already granted device capabilities;
- the card explains what will be missing and provides a system notification-settings fallback;

The restoration notification contains no pairing code, auth token, raw tool argument, file content or other secret-bearing data.

## Future UI-control session

UI control is not currently implemented and the standard build does not declare an autonomous `AccessibilityService` for agents. See [ADR 0003](decisions/0003-ui-control-policy.md).

Future UI-control must remain a separate opt-in capability rather than inheriting authority from the base relay or ordinary embedded-helper setup. Typed Android/helper operations are preferred for actions. If screen pixels are needed, the user must explicitly start a short-lived MediaProjection session and accept the system capture prompt for each new capture session. The projection runs only with the required `mediaProjection` foreground-service type and is never silently restored after reboot.

A future setup card must appear only after a concrete compliant UI-control implementation exists. TypedDroid must not add a placeholder Accessibility permission/service merely to satisfy the wizard roadmap.

## Capability matrix

| Capability | Base | Notifications | SAF folder | Usage Access | Embedded helper | Future UI-control session |
| --- | :---: | :---: | :---: | :---: | :---: | :---: |
| Bridge connection | ✓ | | | | | |
| Battery/memory/storage totals | ✓ | | | | | |
| Launcher-visible app list | ✓ | | | | | |
| Notification-drawer status/history | | ✓ | | | | |
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
- owned helper loss/reboot restoration through Wireless Debugging;
- the privileged operations themselves through the configured Bridge Host device channel.
