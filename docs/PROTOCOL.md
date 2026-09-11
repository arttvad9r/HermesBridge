# Relay protocol draft

Status: design draft for V1. This is intentionally small and should be implemented only after threat-model review.

## Transport

Android opens an outbound `wss://` connection to the Hermes Bridge relay.

The relay does not initiate a network connection to the phone. This allows the phone to work behind carrier NAT, on mobile data and while changing networks.

Both Android and the relay enforce a 256 KiB WebSocket frame limit. Oversized application data must use a dedicated bounded transfer mechanism rather than increasing the control-channel frame size.

List-like Android results are bounded independently of that transport ceiling. `apps.list` and `apps.usage` return at most 128 app entries per command result, bound remote label/version strings, and expose `count`, `totalVisibleCount` and `truncated` so Hermes can distinguish a complete result from a bounded projection. The larger launcher-visible repository remains available locally for package-visibility policy checks; remote truncation must not widen or redefine that security boundary.

As a final shared backstop, Android also limits every serialized `command.result` payload to 224 KiB, reserving 32 KiB for the surrounding control-channel envelope. Tool-specific truncation remains preferred because it preserves useful data. If a handler still produces a larger result, the router fails closed with a small `result_too_large` error instead of sending an oversized frame and entering a reconnect/retry loop.

## Envelope

Every application message uses a versioned envelope:

```json
{
  "v": 1,
  "id": "01J...",
  "type": "command.request",
  "deviceId": "device_...",
  "timestamp": "2026-09-09T12:00:00Z",
  "payload": {}
}
```

Required properties:

- `v`: protocol version;
- `id`: globally unique message ID;
- `type`: strict message type;
- `deviceId`: server-issued opaque device ID after pairing;
- `timestamp`: informational UTC timestamp;
- `payload`: schema defined by `type`.

Unknown versions/types are rejected.

## Pairing

1. User requests pairing from Hermes/relay.
2. Relay creates a short-lived one-time code.
3. User enters/scans it in Hermes Bridge.
4. Android creates or loads a P-256 signing key in Android Keystore.
5. Phone opens TLS connection and submits:
   - pairing code;
   - public key;
   - app/protocol version;
   - non-sensitive device label.
6. Relay validates code, expiry and rate limits.
7. Relay binds the public key to a new opaque `deviceId`.
8. Pairing code is invalidated.
9. Phone receives the device binding and immediately starts authenticated-session setup.

The pairing code is never used as a durable secret.

## Reconnect authentication

1. Phone opens TLS connection and identifies `deviceId`.
2. Relay returns a random challenge nonce.
3. Phone signs `protocolVersion || deviceId || challenge`.
4. Relay verifies the signature against the bound public key.
5. Relay issues an ephemeral session identifier.
6. Commands are accepted only after this state.

## Message types

Initial implemented/control set:

- `session.hello`
- `auth.challenge`
- `auth.response`
- `auth.ok`
- `heartbeat.ping`
- `heartbeat.pong`
- `command.request`
- `command.result`
- `approval.request`
- `device.revoke.request`
- `device.revoke.ok`
- `error`

## Command request

```json
{
  "tool": "device.health",
  "requestId": "req_...",
  "arguments": {}
}
```

Rules:

- tool must exist in the local allowlist;
- arguments must pass a strict schema;
- local policy decides allow / approval / deny;
- Android accepts request IDs of 1–128 characters matching the bounded protocol identifier alphabet;
- while a request ID remains in the Android replay table, it is bound to `SHA-256(tool + canonical arguments)`; the same ID with another payload fails closed with `request_id_conflict`;
- concurrent exact duplicates of one request ID share the same in-flight execution rather than executing the tool twice;
- for the current non-idempotent tools (`files.delete`, install, uninstall, force-stop and permission revoke), a terminal result is retained in the bounded replay table and returned to exact duplicates without running the operation again;
- `approval_required` is deliberately non-terminal in replay accounting; local approval is bound to the exact normalized action arguments rather than `requestId`, so a supported keyed retry may reuse the same request ID after approval while an unkeyed retry still receives a fresh relay-generated ID;
- read-only duplicate requests may be evaluated again because repeating those observations does not mutate device state;
- the replay table is in-memory and bounded to 200 request IDs; it is an in-process duplicate-execution guard, not a claim of durable exactly-once delivery.

The relay HTTP command request accepts an optional retry identity alongside the typed tool and arguments:

```json
{
  "tool": "apps.forceStop",
  "arguments": {"packageName": "com.example.app"},
  "idempotencyKey": "retry:01J..."
}
```

If `idempotencyKey` is omitted, the relay preserves the original behavior and allocates a fresh UUID for the protocol `requestId`. If it is supplied, the relay validates the same 1–128 character request-ID alphabet and propagates the key as the protocol `requestId`. While that keyed command is pending, an exact concurrent duplicate shares the same in-flight result and sends no second WebSocket command; the same key with another tool or JSON argument object fails closed with `request_id_conflict` and cannot replace the original waiter.

The Hermes MCP adapter exposes optional `idempotency_key` parameters for `force_stop_app`, `uninstall_app`, `revoke_app_permission`, `delete_path` and `install_apk`. For retry-safe use, the caller chooses a key before the first invocation and reuses the exact same key with the exact same action arguments after `approval_required` or after a lost/unknown MCP or HTTP response. Calls without a key retain the fresh-ID behavior and are not safe retries of an unknown mutation outcome.

### APK install staging identity

`apps.install` additionally depends on stable relay-created artifact credentials. For a keyed MCP install, the adapter validates the command idempotency key before upload and derives a separate staging identity as SHA-256 of `(device_id, idempotency_key)`. It sends the derived value in the bounded `X-Hermes-Apk-Staging-Key` header to `POST /api/v1/apk-artifacts`; the original idempotency key is still used as the Android protocol `requestId`.

The relay reads and hashes every upload. The first use of one staging identity is bound to the relay-verified sanitized file name, byte length and SHA-256 plus the resulting staged descriptor. An exact retry with that same identity and verified content while the artifact remains live returns the identical `artifactId`, `downloadToken`, size, hash and expiry without extending TTL. Reuse of the same staging identity with changed name, length or bytes fails before command dispatch with HTTP 409 `apk_staging_conflict`.

Unkeyed staging remains fresh and always creates a new descriptor. This prevents a genuinely new install attempt for the same APK from accidentally inheriting an older artifact's remaining TTL. If a keyed artifact expires, reuse of its staging identity fails explicitly with HTTP 409 `apk_staging_expired`; a genuinely new attempt must use a new install idempotency key. The staging table is bounded to 200 identities; when all retained entries still point to live artifacts, a new keyed stage fails closed with `apk_staging_busy` rather than evicting a live retry identity.

The relay does not remove `artifactId` or `downloadToken` from Android's replay fingerprint. Relay restart loses staging correlation and therefore terminates the install retry guarantee; durable exactly-once install semantics are not claimed.

All current mutating/privileged tools still require an exact, one-use Android approval before their actual mutation. Therefore process death cannot silently turn a replayed destructive request into a second mutation: after approval state is lost, a retry must obtain a new local approval. The previous operation's outcome can nevertheless be unknown after a crash, so Hermes must not automatically approve/retry such a request merely because the transport reconnected.

The relay keyed waiter table, APK staging table and Android replay table are all process-local. A relay restart loses relay-side in-flight/staging correlation; a stable-argument retry can still benefit from Android replay protection if its raw protocol arguments remain identical, while install cannot reconstruct the old descriptor after relay restart. Android process death removes the replay proof entirely, so the key does not provide durable exactly-once semantics across Android process recreation.

## Approval notifications

Android remains the only authority for V1 approval state. A relay or Hermes process must not be able to turn a pending local ticket into an approved one because the threat model includes a compromised VPS.

When a typed command requires approval:

1. Android creates or reuses a local approval ticket bound to the canonical normalized tool arguments.
2. Android returns `approval_required` for the command and may additionally emit `approval.request` over the already authenticated device WebSocket.
3. `approval.request` contains only target-free metadata:
   - `approvalId`;
   - one currently allowlisted approval tool name;
   - that tool's expected `MUTATING` or `PRIVILEGED` risk class;
   - relative `expiresInMillis`, capped at 10 minutes.
4. Package names, permission names, SAF paths, APK filenames, raw arguments and the Android approval-card display summary do not cross this notification boundary.
5. Relay validates the authenticated `deviceId`, approval ID, exact allowlisted `tool↔risk` pair and TTL, then stores at most 200 notification records in memory. Relay computes its own local expiry from the relative TTL.
6. The authenticated admin/Hermes side can read unexpired notification records with `GET /api/v1/devices/{deviceId}/approvals` or the MCP `pending_approvals` tool. These records are not authoritative ticket state and can remain until TTL expiry after local resolution.
7. Approval still happens locally on Android. The existing exact-argument fingerprint, expiry and one-use consume rules remain authoritative.
8. After local approval, Hermes retries the same typed tool call and arguments. A caller that supplied an idempotency key reuses that same key; for install, the same live staging identity must also resolve to the same staged descriptor. Otherwise the relay assigns a fresh protocol `requestId`. In every case the approval remains applicable because it is bound to the normalized action fingerprint rather than the transport ID. Changed action arguments require another ticket.

Current notification tools are limited to `files.delete`, `apps.install`, `apps.uninstall`, `apps.forceStop` and `apps.revokePermission`. Unknown tools or mismatched risk classifications remain local and are not queued for remote notification.

There is deliberately **no** `approval.result`, relay `approve` endpoint or MCP approval tool in this phase. A normal Telegram callback, relay admin token or other credential available on the same VPS cannot be trusted as an approval authority under the compromised-VPS model. Fully remote approval requires a separate design with an independently verifiable user-controlled signature whose private key is not available to the VPS.

When the device pairing identity is revoked, missing or invalidated for repair, Android clears its local pending approvals and unsent remote-notification queue so stale tickets cannot be associated with a future `deviceId`.

## Reliability

- heartbeat while connected;
- exponential reconnect backoff with jitter;
- connection resumes after Wi-Fi/mobile handover;
- only bounded in-memory queues before durable queue design is reviewed;
- bounded request-ID replay protection prevents duplicate in-process execution of current destructive tools without pretending to provide durable exactly-once semantics;
- optional relay/MCP idempotency keys let exact mutating requests reuse that Android replay identity across lost MCP/HTTP responses, with relay-side exact in-flight coalescing and payload-conflict rejection;
- keyed APK staging preserves install protocol arguments only for the same explicit device-scoped retry identity and relay-verified content; unkeyed staging remains fresh;
- results include structured error codes, not only strings;
- list-like app results include explicit completeness metadata instead of silently exceeding the WebSocket frame budget;
- a shared 224 KiB `command.result` payload backstop converts any remaining oversized result into `result_too_large` before replay caching/audit/transport;
- approval notifications are best-effort convenience data: losing one never changes or weakens the local approval ticket.

## Not in V1

- arbitrary server-to-phone socket tunnels;
- raw MCP exposed over the public internet;
- general shell transport;
- custom end-to-end encryption layered over TLS;
- offline destructive command queue without explicit expiry;
- VPS-authoritative remote approval.
