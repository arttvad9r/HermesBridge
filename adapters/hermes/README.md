# Hermes adapter

Hermes is an optional MCP host for Hermes Bridge. There is no Hermes-specific Android protocol or privileged backend.

Install and start the generic MCP adapter as described in `../../docs/MCP.md`, then point Hermes at the same `android-bridge-mcp` executable:

```yaml
mcp_servers:
  android_bridge:
    command: "/home/USER/.local/bin/android-bridge-mcp"
    args: []
    enabled: true
```

The generic adapter reads the local Bridge Host token from `~/.config/hermesbridge/admin-token` by default. Hermes receives exactly the same tool schemas and Android approval policy as any other MCP host.
