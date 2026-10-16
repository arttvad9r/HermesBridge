# Security model

TypedDroid gives an AI agent the ability to observe and potentially mutate a personal phone. This makes the local policy boundary more important than ordinary remote-control authentication.

## Threats considered

### Stolen relay/device credential

An attacker obtains a token or attempts to impersonate a paired Android device or local Bridge Host client.

Mitigations:

- short-lived pairing codes;
- long-term device key generated on the phone;
- Android Keystore storage;
- challenge/response authentication;
- credential revocation;
- TLS;
- never log pairing secrets.

### Prompt injection reaches the agent

The AI agent reads hostile content that attempts to make it call phone tools.

Network authentication does not mitigate this.

Mitigations:

- SAFE mode keeps the typed, default-deny tool surface;
- TRUSTED mode is an explicit local grant for the broader full-control surface;
- local risk classification and pairing-scoped trust state;
- user approval for ordinary mutating/privileged/UI-control operations unless a separately granted trusted capability applies;
- raw shell is denied in SAFE mode and available only after the user explicitly enables TRUSTED mode and the owned privileged helper is active;
- command schemas, time/output limits and local audit remain in force around the transport;
- lockscreen credential automation remains forbidden.

### Over-broad Android permission

An optional capability grants more authority than the user expected.

Mitigations:

- capability groups shown separately;
- base mode works without privileged helper/UI-control;
- Android 13+ notification permission is requested explicitly and does not gate the relay;
- Usage Access and SAF are separate opt-in capabilities;
- UI control is a separate opt-in capability;
- the shell-UID helper is a separate opt-in capability;
- no `QUERY_ALL_PACKAGES`; embedded ADB discovery uses Android NSD rather than package visibility.

### Compromised agent host

A compromised AI agent, MCP process, or local Bridge Host attempts unrestricted actions.

Mitigations:

- Android-side policy is authoritative;
- each pairing starts in SAFE mode and TRUSTED must be enabled explicitly on the phone;
- local approval remains the default for high-impact typed commands;
- raw shell is a named TRUSTED-only capability rather than an accidental passthrough;
- per-device credential revocation;
- tool allowlist enforced on device;
- relay-supplied error messages are treated as untrusted; Android surfaces only fixed local text selected from an allowlisted protocol error code;
- Android command-result errors cross the phone→relay boundary only as a bounded protocol-style code plus deterministic local text selected at the top-level command router; arbitrary local exception messages, system stderr, paths, URLs and backend detail are not forwarded;
- remote approval notifications are informational only: the Bridge Host admin token, agent process and notification callbacks cannot approve, deny or consume an Android ticket;
- approval notifications contain only device/approval IDs, an exact allowlisted tool/risk pair and a short relative TTL; target package names, permission names, SAF paths, APK filenames, raw arguments and the local approval-card summary remain on-device;
- unknown approval tools or mismatched risk classifications are never queued for the remote notification path;
- losing pairing identity clears pending local approvals and unsent notification records before a future re-pair;
- command outcomes and approval decisions are recorded locally on the phone.

A future fully remote approval mechanism must remain independently verifiable when the local host is hostile. In particular, a signing credential stored or processed on the same host is not an independent approval factor. Any such design must bind the user's decision to the Android-created ticket/fingerprint and be verified on-device.

## Risk classes

| Risk | Examples | Default |
| --- | --- | --- |
| READ_ONLY | health, list apps, inspect file metadata | Allow |
| MUTATING | delete file, uninstall app | Require approval |
| PRIVILEGED | install APK, force-stop, permission changes | Require approval |
| UI_CONTROL | tap, swipe, input text | Require approval / explicit session |
| RAW_SHELL | arbitrary shell command as Android shell UID | Deny in SAFE; allow only in explicit TRUSTED mode |

Every new pairing starts in SAFE mode. TRUSTED is a persistent, pairing-scoped local grant and can be revoked by returning to SAFE or unpairing the agent.

## Always-forbidden / separately gated surfaces

Even TRUSTED does not mean TypedDroid should silently automate Android lockscreen credentials. The following remain forbidden or require a separate future capability design:

- lockscreen PIN/password entry or extraction;
- hidden credential theft/bypass;
- implicit root escalation when the user has not separately granted a root backend.

SAFE mode additionally denies raw shell, arbitrary Intent/content-URI passthroughs and other generic full-control surfaces.

## Screen-capture boundary

MediaProjection is an explicit local capability, not an implicit extension of the relay connection. The current pixel path is intentionally narrower than a screenshot tool:

- each Android consent grant is used for at most one `MediaProjection.createVirtualDisplay()` call;
- the virtual display is non-secure, so Android continues to blank `FLAG_SECURE` / protected window content instead of TypedDroid attempting to capture it;
- the capture surface is aspect-ratio preserving and bounded to at most 1,600 pixels on either axis and 1,500,000 total pixels;
- the process-local copied RGBA frame is therefore bounded to 6,000,000 bytes;
- exactly one frame is acquired;
- before that temporary RGBA copy is erased, TypedDroid masks locally derived system-bar and display-cutout edge regions with opaque black pixels;
- when the software keyboard is visible, its visibility-sensitive IME inset is read from a fresh `WindowInsets` snapshot after the frame arrives and unioned with the system-edge mask; `getInsetsIgnoringVisibility(Type.ime())` is not used;
- if the maximum-window source geometry changed between capture setup and frame redaction, the redaction path fails closed rather than applying fresh insets in a stale coordinate system;
- after local masking, the copied byte array is overwritten immediately, and the `Image`, `ImageReader`, `VirtualDisplay`, and `MediaProjection` resources are released;
- no screenshot pixels enter `UiCaptureSessionRuntime`, app-private files, logs, audit history, Android intents, protocol models, relay state, MCP results, or Telegram notifications;
- there is no remote screenshot/capture command and the relay cannot start MediaProjection consent.

This edge-mask policy does **not** claim semantic redaction of arbitrary app-owned text fields, messages, form values, notifications rendered inside app content, or other sensitive pixels. Those cases remain outside the current pixel-consumer boundary because captured pixels are not retained or transported.

Any future screenshot retention, semantic app-content redaction, serialization, transport, remote trigger, or repeated-frame processing is a separate privacy/security boundary and must be reviewed explicitly rather than inheriting permission from this local one-frame primitive.

## Pairing

Pairing code properties:

- human-enterable;
- one-time;
- short expiry;
- rate-limited server side;
- only valid over TLS;
- never becomes the long-term device credential.

The phone generates a P-256 signing key in Android Keystore. After successful pairing the relay stores the public key/device binding. Reconnect authentication uses a server challenge signed by the phone key.

Do not invent an additional encryption protocol over TLS in V1.

## Secrets

Never store long-term secrets in:

- SharedPreferences plaintext;
- logs;
- crash messages;
- exported files;
- QR screenshots.

Use Android Keystore for private keys. If a bearer/session token is needed, encrypt it with a Keystore-backed key or derive short-lived sessions from challenge authentication.

## Logging

The implemented Android audit history is deliberately smaller than a diagnostic/debug log. It stores at most the latest 200 entries in app-private storage.

Command entries contain only:

- timestamp;
- a tool name from the fixed audited allowlist, otherwise `unknown_tool`;
- success/error outcome;
- a normalized bounded error code, otherwise `other_error`.

Approval entries persist only the allowlisted tool name and approved/denied outcome. Target package names, permissions, SAF paths and APK metadata remain on-device only for the live approval UI and are not persisted in audit history.

The audit history intentionally does **not** persist raw command arguments or raw error messages. It therefore does not record APK download tokens, relay/auth credentials, remote URLs, SAF paths supplied as command arguments, file contents, package-install bytes, pairing codes or arbitrary unknown tool strings.

The history screen is an internal non-exported activity and can be cleared locally by the user. The foreground-service notification provides a local shortcut to it when notifications are available.

## Reboot and embedded-helper recovery

The base authenticated agent connection and the shell-UID helper are separate capability layers. Losing the helper after reboot/process death must not be represented as loss of the base connection and must not widen access.

TypedDroid persists its own ADB identity and whether pairing previously succeeded, but not a reusable shell command channel. After reboot the app may rediscover `_adb-tls-connect._tcp` while Wireless Debugging is enabled, authenticate with the paired key, and bootstrap a new helper. If Android revoked that key, the user must repeat the six-digit pairing flow.

The helper binds TCP only to the device loopback interface and authenticates every RPC with a random 256-bit bootstrap secret stored in TypedDroid private state. Android SELinux prevents an ordinary app domain from connecting to a shell-owned Unix stream socket, so loopback is used deliberately. The listener is never bound to Wi-Fi/cellular interfaces; request frames are tightly bounded; and SAFE/TRUSTED authorization remains enforced in the app regardless of helper availability.


## Android component exposure

- activities/services/receivers are `exported=false` unless Android requires otherwise;
- launcher activity is exported only for launcher intent;
- the audit-history activity is `exported=false`;
- no cleartext network traffic;
- no externally reachable local MCP server in the default product topology;
- package visibility remains launcher-scoped;
- any deep link used for pairing must validate origin and nonce.

## Dependency policy

Before upgrading privileged/tooling dependencies:

1. review release notes/security advisories;
2. inspect permission or tool-surface changes;
3. run tests/build;
4. re-run the allowlist audit;
5. never auto-enable newly added upstream tools.
