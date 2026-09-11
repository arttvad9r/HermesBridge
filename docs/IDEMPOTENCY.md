# Command replay and idempotency boundaries

Hermes Bridge uses the protocol `requestId` as the identity of a command request on the Android control channel. The Android replay guard treats an exact duplicate as the same replay entry only when both the `requestId` and the canonical tool arguments are unchanged.

## Android in-process replay protection

Within one running Android bridge process, redelivery of an exact mutating protocol request is protected from duplicate execution:

1. the first request follows the normal local policy and approval path;
2. Android executes the mutation at most once for that retained replay entry;
3. the terminal command result is retained in the bounded in-memory replay table;
4. an exact duplicate with the same request ID and arguments receives that retained result without executing the mutation again and without adding a second audit event.

Concurrent exact duplicates of one request ID also share the same in-flight execution. A reused request ID with a different tool or canonical arguments fails closed with `request_id_conflict`.

This is duplicate-delivery protection on the Android protocol path. It does not by itself provide durable or end-to-end exactly-once delivery.

## Relay and MCP retry keys

The relay HTTP command API accepts an optional `idempotencyKey`. When supplied, the relay validates the same bounded identifier format used by Android request IDs (1–128 characters; first character alphanumeric; remaining characters alphanumeric, `.`, `_`, `:`, or `-`) and propagates that value as the protocol `requestId`. When the key is omitted, the relay preserves the original behavior and allocates a fresh UUID for that HTTP command call.

While a keyed command is pending in the relay:

- an exact concurrent duplicate with the same key, tool, and JSON arguments shares the original in-flight result and does not send a second WebSocket command;
- the same key with a different tool or arguments fails closed with `request_id_conflict` and does not replace the original waiter.

After a relay-side timeout the pending correlation entry is removed. A caller may retry the exact same keyed command; if the original command is still in flight on Android, the Android replay guard coalesces it, and if Android already retained a terminal result, that retained result is returned. This is why the same key must never be reused for a different action.

The Hermes MCP adapter exposes optional `idempotency_key` parameters for the current stable-argument mutating tools:

- `force_stop_app`;
- `uninstall_app`;
- `revoke_app_permission`;
- `delete_path`.

For retry-safe use, the caller chooses the key before the first invocation and reuses the exact same key with the exact same normalized action arguments after `approval_required` and after a lost/unknown MCP or HTTP response. Calls that omit the key retain the legacy fresh-request behavior and are not safe retries of an unknown mutation outcome.

## APK install is not yet retry-safe through MCP

`install_apk` is intentionally excluded from the first end-to-end idempotency-key contract. The MCP adapter stages the APK on every invocation, and staging produces a new `artifactId` and `downloadToken`. Those values are part of the protocol arguments, so reusing an old request ID with a newly staged artifact correctly fails Android replay validation with `request_id_conflict`.

Making install retry-safe requires separately reviewed staging correlation that reuses the exact verified staged artifact, or an equivalent design that keeps the protocol arguments stable across retries. Until then, an unknown MCP/HTTP install outcome must not be retried automatically.

## Request IDs and approvals are separate identities

The replay guard and local approval manager intentionally bind different things:

- the replay guard binds a `requestId` to one tool plus canonical protocol arguments;
- an approval ticket is bound to one exact normalized action fingerprint (`tool + normalized arguments`) and is consumed once; it is not bound to `requestId`.

`approval_required` is deliberately non-terminal in replay accounting. For a supported keyed mutation, the caller can therefore reuse the same idempotency key and exact arguments after the user grants the existing local approval. Android then evaluates the same replay entry again, consumes the matching approval ticket once, and retains the eventual terminal result.

Changing only the request ID still creates a distinct replay-guard entry, and it does not by itself invalidate an already approved, still-unconsumed ticket for the exact same normalized action. This keeps user approval attached to the action being authorized while request IDs remain transport/replay identities.

## Process death, relay restart, and bounded memory

The Android replay table is deliberately in-memory and bounded to 200 request IDs. After Android process death the bridge cannot prove whether a previous mutation completed, and the replay table no longer contains its terminal result. Approval state is also process-local and is cleared with the process.

Relay in-flight correlation is also process-local. A relay restart loses its pending waiter table, although a subsequent retry using the same key can still benefit from Android replay protection if the Android process and replay entry survived.

Therefore the current idempotency key provides safe retry across lost MCP/HTTP responses only while the Android replay identity still exists. It does not provide durable exactly-once semantics across Android process death. A persistent operation journal would be required for that stronger guarantee.

If the outcome is unknown after Android process death, callers must surface the ambiguity rather than blindly repeating the mutation.
