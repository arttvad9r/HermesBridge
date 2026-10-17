# ADR 0006: Pinned direct network transport with Internet fallback

## Status

Accepted for the full-control development branch. Hosted rendezvous/relay remains future work.

## Context

The Android agent channel already authenticates the device above WebSocket/TLS, but the first production topology assumed one fixed Internet-reachable WSS endpoint. That makes local traffic unnecessarily indirect and makes reconnect after Wi-Fi/mobile handover wait for ordinary exponential backoff. Simply binding the existing relay to `0.0.0.0` would also expose the loopback admin API, which is unacceptable.

## Decision

1. Keep the relay admin connector on loopback only.
2. Add a separate optional TLS device-ingress connector on a distinct port.
3. Reject `/api/v1/*` when the request arrived on the device-ingress connector.
4. Generate a user-private self-signed EC certificate for managed direct ingress.
5. After device authentication, advertise only bounded private literal WSS candidates with the SHA-256 leaf-certificate pin.
6. Android validates/persists unexpired hints and prefers pinned direct candidates before the configured Internet WSS endpoint.
7. Direct and Internet routes use the same persisted device identity and challenge/response protocol.
8. APK/file HTTP data plane follows the endpoint of the current authenticated session.
9. Android `NetworkCallback` forces a fresh session and interrupts reconnect backoff when the default network changes.
10. Do not claim arbitrary NAT traversal. A hosted rendezvous/relay is an optional later endpoint provider, not a new Android authority.

## Security consequences

A hostile LAN cannot substitute another direct relay without the pinned certificate. The self-signed certificate is acceptable because trust is established by the authenticated control session rather than by local DNS/CA state. Admin credentials and routes remain unavailable on the direct listener. If direct setup fails, the system falls back to the existing WSS endpoint rather than weakening TLS.

## Operational consequences

The normal product still exposes one agent-side command, `typeddroid-mcp`. Direct ingress is an internal optimization and may be disabled with `TYPED_DROID_DIRECT_ENABLED=0`. Physical Wi-Fi↔cellular handover and OEM background behavior remain release-validation items.
