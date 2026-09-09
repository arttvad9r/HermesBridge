# ADR 0001: Phone-initiated outbound relay connection

Status: Accepted

## Context

Normal ADB-over-Wi-Fi expects the phone and workstation to be mutually reachable and is a poor daily UX for a roaming phone. The product goal requires Hermes on a VPS to work while the laptop is off and the phone is on cellular or unrelated Wi-Fi.

## Decision

The Android app initiates and maintains an authenticated TLS/WebSocket connection to a relay near Hermes.

No inbound phone port is required in the default architecture.

## Consequences

Positive:

- works behind carrier NAT;
- works across network changes;
- removes laptop from runtime path;
- no user-visible IP/port configuration.

Negative:

- requires a relay component;
- requires reconnect/session design;
- relay compromise becomes part of the threat model, so Android-side policy remains authoritative.
