# Relay protocol draft

Status: design draft for V1. This is intentionally small and should be implemented only after threat-model review.

## Transport

Android opens an outbound `wss://` connection to the Hermes Bridge relay.

The relay does not initiate a network connection to the phone. This allows the phone to work behind carrier NAT, on mobile data and while changing networks.

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

Initial set:

- `session.hello`
- `auth.challenge`
- `auth.response`
- `auth.ok`
- `heartbeat.ping`
- `heartbeat.pong`
- `command.request`
- `command.accepted`
- `command.result`
- `approval.request`
- `approval.result`
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
- a duplicate `requestId` must not repeat a completed destructive action.

## Approvals

For commands requiring approval:

1. Android marks command pending and emits `approval.request`.
2. Approval can be resolved locally or by an authenticated Hermes-side approval channel according to user policy.
3. Expired requests are denied.
4. Approval binds to the exact request/tool/arguments hash. Editing parameters invalidates it.

## Reliability

- heartbeat while connected;
- exponential reconnect backoff with jitter;
- connection resumes after Wi-Fi/mobile handover;
- only bounded in-memory queues before durable queue design is reviewed;
- destructive commands use idempotency keys;
- results include structured error codes, not only strings.

## Not in V1

- arbitrary server-to-phone socket tunnels;
- raw MCP exposed over the public internet;
- general shell transport;
- custom end-to-end encryption layered over TLS;
- offline destructive command queue without explicit expiry.
