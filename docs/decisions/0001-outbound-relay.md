# ADR 0001: Phone-initiated outbound relay connection

Status: Superseded by ADR 0004

## Context

The original product direction assumed an always-available remote agent host while the phone roamed across unrelated networks. That assumption is no longer part of the current architecture; see ADR 0004.

## Decision

The Android app initiates and maintains an authenticated TLS/WebSocket connection to a relay endpoint.

No inbound phone port is required in the default architecture.

## Consequences

Positive:

- works behind carrier NAT;
- works across network changes;
- allows a host to be reachable across network changes;
- no user-visible IP/port configuration.

Negative:

- requires a relay component;
- requires reconnect/session design;
- relay compromise becomes part of the threat model, so Android-side policy remains authoritative.
