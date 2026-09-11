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

The Hermes MCP adapter exposes optional `idempotency_key` parameters for the current mutating tools:

- `force_stop_app`;
- `uninstall_app`;
- `revoke_app_permission`;
- `delete_path`;
- `install_apk`.

For retry-safe use, the caller chooses the key before the first invocation and reuses the exact same key with the exact same normalized action arguments after `approval_required` and after a lost/unknown MCP or HTTP response. Calls that omit the key retain the legacy fresh-request behavior and are not safe retries of an unknown mutation outcome.

## APK install staging correlation

`apps.install` has raw protocol arguments that include a relay-created `artifactId` and `downloadToken`. Command-key reuse is therefore safe only when an exact retry gets the exact same staged descriptor. Those artifact credentials remain part of Android's replay fingerprint.

For a keyed `install_apk`, the MCP adapter first validates the caller's install `idempotency_key`, then derives a staging identity as SHA-256 of `(device_id, idempotency_key)`. The derived value is sent only to the relay staging endpoint as `X-Hermes-Apk-Staging-Key`; the original caller key remains the Android protocol `requestId`. Device scoping means the same caller key used independently for two phones does not collide in the staging table.

The relay reads and hashes every uploaded APK before deciding whether a keyed stage is an exact retry. The first use of one staging identity is bound to the relay-verified tuple `(sanitized file name, byte length, SHA-256)` and to one staged descriptor. Re-uploading the same verified content with that same staging identity while its artifact is still live returns the identical `artifactId`, `downloadToken`, size, hash and expiry without extending the expiry. Reusing the staging identity with a changed file name, length or bytes fails closed with HTTP 409 `apk_staging_conflict` before any Android command is dispatched.

Unkeyed staging deliberately remains fresh. Uploading the same APK again without an install idempotency key creates a new descriptor, so a genuinely new install attempt does not accidentally inherit the remaining lifetime of an older staged artifact.

A keyed staging identity is retained in bounded process memory. If its artifact expires, the old binding is kept long enough to make reuse fail explicitly with HTTP 409 `apk_staging_expired` rather than silently creating new raw command arguments under the old install identity. The caller must choose a new install idempotency key for a genuinely new attempt after that boundary. The relay also bounds retained staging identities; if all retained slots still refer to live artifacts, a new keyed stage fails closed with `apk_staging_busy` instead of evicting a live retry identity.

A relay restart loses both staged-artifact metadata and staging bindings. The install retry contract therefore does not survive relay process loss. It also does not claim durable exactly-once semantics across Android process death.

## Request IDs and approvals are separate identities

The replay guard and local approval manager intentionally bind different things:

- the replay guard binds a `requestId` to one tool plus canonical protocol arguments;
- an approval ticket is bound to one exact normalized action fingerprint (`tool + normalized arguments`) and is consumed once; it is not bound to `requestId`.

`approval_required` is deliberately non-terminal in replay accounting. For a supported keyed mutation, the caller can therefore reuse the same idempotency key and exact arguments after the user grants the existing local approval. Android then evaluates the same replay entry again, consumes the matching approval ticket once, and retains the eventual terminal result.

Changing only the request ID still creates a distinct replay-guard entry, and it does not by itself invalidate an already approved, still-unconsumed ticket for the exact same normalized action. This keeps user approval attached to the action being authorized while request IDs remain transport/replay identities.

## Process death, relay restart, and bounded memory

The Android replay table is deliberately in-memory and bounded to 200 request IDs. After Android process death the bridge cannot prove whether a previous mutation completed, and the replay table no longer contains its terminal result. Approval state is also process-local and is cleared with the process.

Relay command in-flight correlation and keyed APK staging correlation are also process-local and bounded. A relay restart loses those tables. A subsequent stable-argument retry can still benefit from Android replay protection if its raw protocol arguments remain identical, but install cannot reconstruct the old artifact descriptor after relay restart and must surface that retry boundary rather than restage under the old identity.

Therefore the current idempotency key provides safe retry across lost MCP/HTTP responses only while the required replay identity and, for install, its exact keyed staged descriptor still exist. It does not provide durable exactly-once semantics across Android process death or relay restart. A persistent operation journal and persistent staging correlation would be required for that stronger guarantee.

If the outcome is unknown after those boundaries are lost, callers must surface the ambiguity rather than blindly repeating the mutation.
