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

## Optional direct device ingress

For the managed `typeddroid-mcp` runtime, direct ingress is enabled opportunistically by default. When `keytool`, a private/overlay address, and a free direct port are available, MCP creates a user-private long-lived EC certificate and starts an additional WSS listener (default port `8443`). The admin listener remains `127.0.0.1:8080`.

Relevant overrides:

```text
TYPED_DROID_DIRECT_ENABLED=0|1
TYPED_DROID_DIRECT_PORT=8443
TYPED_DROID_DIRECT_HOSTS=192.168.1.20,100.64.1.2
```

Only private literal addresses, IPv6 ULA, and RFC6598/overlay addresses are advertised. Public addresses, loopback, DNS names, and unscoped IPv6 link-local addresses are rejected by Android. The device learns the direct certificate pin only after authenticated relay login; a mismatched pin fails closed. If direct setup is unavailable, normal MCP startup continues and the configured Internet WSS route remains the fallback.
