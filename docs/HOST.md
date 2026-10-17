# Internal relay runtime

TypedDroid still uses a small local Kotlin relay process for authenticated Android device sessions, command routing and bounded transfer data-plane endpoints. It is **not** a separate normal user-facing component anymore: `typeddroid-mcp` starts or reuses it automatically and coordinates its lifetime.

## Topology

```text
Any MCP-capable agent
        |
        | local stdio
        v
typeddroid-mcp
        |
        | managed loopback HTTP + local token
        v
internal relay (127.0.0.1)
        ^
        | authenticated Android device channel
        |
TypedDroid Android app
```

The admin API stays on loopback. Android initiates its connection; the phone never exposes an inbound management port.

## Normal use

Install once:

```bash
./install.sh
```

Then configure the chosen MCP host to run:

```text
~/.local/bin/typeddroid-mcp
```

There is no `typeddroid-host` startup step. The installed runtime lives under:

```text
~/.local/share/typeddroid/relay-runtime/
```

Runtime state remains user-owned:

```text
~/.config/typeddroid/admin-token
~/.local/state/typeddroid/
~/.local/share/typeddroid/apks/
~/.local/share/typeddroid/transfers/
```

Concurrent MCP processes share one relay through a local locked lease registry. A detached watchdog removes stale leases and shuts down the relay when the final managed MCP process disappears, including forced stdio termination.

For relay development/debugging only, `./host/run-local-host.sh` may still be used directly.

## Android device ingress

The current device transport accepts a canonical trusted `wss://HOST[:PORT]/ws/device` endpoint. The mechanism that makes this endpoint reachable from the phone is transport infrastructure, not an agent dependency. It can be provided by a LAN reverse proxy, private overlay network or tunnel until Phase 6 replaces this with transport that is independent of local network reachability.

Device/data-plane routes that may need proxy reachability are narrowly scoped, including:

- `/health`
- `/ws/device`
- token-gated `/device-artifacts/*`
- token-gated `/file-artifacts/*`
- one-use token-gated `/device-file-uploads/*`

Never expose `/api/v1/*`; it is the loopback admin boundary.
