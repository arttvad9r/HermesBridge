# Bridge Host

Bridge Host is the local computer-side process that brokers authenticated Android device connections and the generic MCP adapter. It runs as a normal user process and is not tied to Hermes Agent.

## Topology

```text
Any MCP-capable agent
        |
        | local stdio
        v
Generic MCP adapter
        |
        | loopback HTTP + local token
        v
Bridge Host (127.0.0.1)
        ^
        | authenticated Android device channel
        |
Android app
```

The admin API is always intended to stay on loopback. Android initiates its connection; the phone never exposes an inbound management port.

## Run locally

Requirements: Java 17+ and Python 3.10+.

```bash
./host/run-local-host.sh
```

The launcher creates user-owned state only:

```text
~/.config/hermesbridge/admin-token
~/.local/state/hermesbridge/
~/.local/share/hermesbridge/apks/
```

It builds the relay distribution from source when necessary and runs it as the current user. No root, systemd, `/opt`, or machine-wide secret is required.

Local health check:

```bash
curl --fail http://127.0.0.1:8080/health
```

The token is deliberately not printed. The generic MCP adapter reads the token file automatically.

## Android device ingress

The current production Android transport accepts only a canonical trusted `wss://HOST[:PORT]/ws/device` endpoint. The mechanism that makes this endpoint reachable from the phone is transport infrastructure, not an agent dependency. It can be provided by a user-controlled LAN reverse proxy, private overlay network, or tunnel.

Only these device-facing routes may be proxied:

- `/health`
- `/ws/device`
- `/device-artifacts/*`

Never expose `/api/v1/*`; it is the local Bridge Host admin boundary.

A future local-device transport can remove the external WSS ingress requirement only if it preserves authentication, typed operations, bounded results and Android-side approvals.
