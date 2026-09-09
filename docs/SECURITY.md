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
- audit trail.

### Over-broad Android permission

An optional capability grants more authority than the user expected.

Mitigations:

- capability groups shown separately;
- base mode works without Shizuku/Accessibility;
- optional permissions are not requested at first launch unless required;
- UI control is a separate opt-in capability;
- Shizuku is a separate opt-in capability.

### Compromised VPS

A compromised relay or Hermes instance attempts unrestricted actions.

Mitigations:

- Android-side policy is authoritative;
- local approval for high-impact commands;
- per-device credential revocation;
- no generic shell endpoint;
- tool allowlist enforced on device.

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

Audit entries should record:

- timestamp;
- Hermes/device identity;
- tool;
- risk class;
- normalized parameters with sensitive fields redacted;
- approval decision;
- result status;
- duration/error category.

Audit logs must not contain file contents, passwords, pairing codes or raw auth tokens.

## Android component exposure

- activities/services/receivers are `exported=false` unless Android requires otherwise;
- launcher activity is exported only for launcher intent;
- no cleartext network traffic;
- no externally reachable local MCP server in the default product topology;
- any deep link used for pairing must validate origin and nonce.

## Dependency policy

Before upgrading privileged/tooling dependencies:

1. review release notes/security advisories;
2. inspect permission or tool-surface changes;
3. run tests/build;
4. re-run the allowlist audit;
5. never auto-enable newly added upstream tools.
