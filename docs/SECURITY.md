# Security model

Hermes Bridge gives an AI agent the ability to observe and potentially mutate a personal phone. This makes the local policy boundary more important than ordinary remote-control authentication.

## Threats considered

### Stolen relay/device credential

An attacker obtains a token or attempts to impersonate Hermes.

Mitigations:

- short-lived pairing codes;
- long-term device key generated on the phone;
- Android Keystore storage;
- challenge/response authentication;
- credential revocation;
- TLS;
- never log pairing secrets.

### Prompt injection reaches Hermes

Hermes reads hostile content that attempts to make the agent call phone tools.

Network authentication does not mitigate this.

Mitigations:

- typed tool surface;
- default-deny registration;
- local risk classification;
- user approval for mutating/privileged/UI-control operations;
- no raw shell;
- no arbitrary intents/content URIs;
- command schemas and limits;
- local audit trail.

### Over-broad Android permission

An optional capability grants more authority than the user expected.

Mitigations:

- capability groups shown separately;
- base mode works without Shizuku/Accessibility;
- Android 13+ notification permission is requested explicitly and does not gate the relay;
- Usage Access and SAF are separate opt-in capabilities;
- UI control is a separate opt-in capability;
- Shizuku is a separate opt-in capability;
- no `QUERY_ALL_PACKAGES`; Shizuku recovery uses one exact package query only.

### Compromised VPS

A compromised relay or Hermes instance attempts unrestricted actions.

Mitigations:

- Android-side policy is authoritative;
- local approval for high-impact commands;
- per-device credential revocation;
- no generic shell endpoint;
- tool allowlist enforced on device;
- relay-supplied error messages are treated as untrusted; Android surfaces only fixed local text selected from an allowlisted protocol error code;
- command outcomes and approval decisions are recorded locally on the phone.

## Risk classes

| Risk | Examples | Default |
| --- | --- | --- |
| READ_ONLY | health, list apps, inspect file metadata | Allow |
| MUTATING | delete file, uninstall app | Require approval |
| PRIVILEGED | install APK, force-stop, permission changes | Require approval |
| UI_CONTROL | tap, swipe, input text | Require approval / explicit session |

The user may later create narrower trust rules, but the shipped default remains conservative.

## Explicitly forbidden tool surface

These must not be exposed to Hermes in V1:

- `run_shell`;
- arbitrary Intent sender;
- arbitrary ContentResolver/content-URI reader/writer;
- lockscreen PIN/password tool;
- unrestricted root command;
- arbitrary filesystem path outside allowed storage roots.

If an upstream dependency exposes one of these, Hermes Bridge must omit it from registration rather than relying on prompting instructions.

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

Approval entries additionally contain the bounded human-facing summary that was already displayed in the local approval card plus approved/denied outcome.

The audit history intentionally does **not** persist raw command arguments or raw error messages. It therefore does not record APK download tokens, relay/auth credentials, remote URLs, SAF paths supplied as command arguments, file contents, package-install bytes, pairing codes or arbitrary unknown tool strings.

The history screen is an internal non-exported activity and can be cleared locally by the user. The foreground-service notification provides a local shortcut to it when notifications are available.

## Reboot and Shizuku recovery

The base relay and Shizuku are separate security/capability layers. Losing Shizuku after reboot must not silently widen access, weaken authentication, or be represented as loss of the base Hermes connection.

Hermes Bridge stores only a boolean that Shizuku previously reached `READY`; it does not persist a Shizuku credential or shell capability. After `BOOT_COMPLETED`, the base authenticated relay reconnect starts independently. A delayed check may show a local restoration notification only when Shizuku had previously been configured and notification permission is currently granted.

The restoration notification contains no pairing code, relay token, raw command argument, file content or package data. It can open Hermes Bridge and may launch only the exact official Shizuku package declared in package visibility. `MY_PACKAGE_REPLACED` does not trigger the reboot reminder. When Shizuku returns to `READY`, the reminder is cancelled.

## Android component exposure

- activities/services/receivers are `exported=false` unless Android requires otherwise;
- launcher activity is exported only for launcher intent;
- the audit-history activity is `exported=false`;
- no cleartext network traffic;
- no externally reachable local MCP server in the default product topology;
- package visibility remains launcher-scoped plus the exact official Shizuku package used for recovery UX;
- any deep link used for pairing must validate origin and nonce.

## Dependency policy

Before upgrading privileged/tooling dependencies:

1. review release notes/security advisories;
2. inspect permission or tool-surface changes;
3. run tests/build;
4. re-run the allowlist audit;
5. never auto-enable newly added upstream tools.
