# Command replay and idempotency boundaries

Hermes Bridge uses the protocol `requestId` as the identity of a command request on the Android control channel. The Android replay guard treats an exact duplicate as the same replay entry only when both the `requestId` and the canonical tool arguments are unchanged.

## In-process replay protection for `apps.forceStop`

Within one running Android bridge process, redelivery of the exact same `apps.forceStop` protocol request is protected from duplicate execution:

1. the first request follows the normal local policy and approval path;
2. Android executes the fixed force-stop operation at most once for that replay entry;
3. the terminal command result is retained in the bounded in-memory replay table;
4. an exact duplicate with the same request ID and arguments receives that retained result without executing force-stop again and without adding a second audit event.

This is duplicate-delivery protection on the Android protocol path. It does not by itself provide durable or end-to-end exactly-once delivery.

## Current relay and MCP boundary

The current relay HTTP command API accepts only a tool name and arguments. The relay allocates a fresh protocol `requestId` for every HTTP command call, and the current Hermes MCP adapter does not expose a caller-supplied request ID.

Therefore a second MCP invocation or a second HTTP command request is a new protocol request, not a safe retry of a response-lost mutation. If the MCP/HTTP outcome of a mutating command is unknown, callers must surface that ambiguity instead of automatically invoking the mutation again.

End-to-end safe retry after a lost MCP/HTTP response would require a separately reviewed idempotency key propagated through MCP and relay into the protocol request, together with relay-side validation and correlation rules. Hermes Bridge does not currently claim that guarantee.

## Request IDs and approvals are separate identities

The replay guard and local approval manager intentionally bind different things:

- the replay guard binds a `requestId` to one tool plus canonical protocol arguments;
- an approval ticket is bound to one exact normalized action fingerprint (`tool + normalized arguments`) and is consumed once; it is not bound to `requestId`.

As a result, changing only `requestId` creates a distinct replay-guard entry, but it does not by itself invalidate an already approved, still-unconsumed ticket for the exact same normalized action. Once that ticket is consumed, another matching mutation requires a new local approval.

This keeps user approval attached to the exact action being authorized while request IDs remain transport/replay identities.

## Process death and bounded memory

The replay table is deliberately in-memory and bounded to 200 request IDs. After process death Android cannot prove whether a previous mutation completed, and the replay table no longer contains its terminal result. Approval state is also process-local and is cleared with the process.

A persistent operation journal would be required for durable exactly-once semantics. Hermes Bridge does not currently implement one.

## Other mutations

The same replay guard retains terminal results for the current destructive/mutating tool set. That protects duplicate delivery of one retained protocol request; it does not make a fresh MCP/HTTP invocation semantically idempotent or make mutations exactly-once across process death.
