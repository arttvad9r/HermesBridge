# ADR 0004: Agent-neutral local Bridge Host

Status: Accepted

## Context

The original architecture coupled normal operation to a specific Hermes deployment and a remote relay host. The Android security model itself does not require that coupling: Android already authenticates the device, validates typed tools, and retains final approval authority.

MCP is now broadly supported by local AI coding/agent hosts through stdio. Keeping the agent boundary local also avoids distributing Bridge Host credentials to a remote provider.

## Decision

Hermes Bridge uses three independent layers:

1. Android Bridge Core and typed Android operations.
2. A local Bridge Host that owns device pairing/routing and binds its admin API to loopback.
3. A generic MCP stdio adapter used by any compatible AI agent.

Hermes Agent is only one optional configuration of layer 3. It has no additional protocol methods or Android privileges.

The Android device channel remains outbound/authenticated. Its network reachability is transport infrastructure and must not be coupled to a particular cloud provider or agent.

## Consequences

Positive:

- no agent lock-in;
- no mandatory remote server deployment;
- credentials stay on the user's computer by default;
- Codex, Claude Code, OpenCode, Cursor, Hermes and other MCP hosts share one tool implementation;
- security policy remains Android-authoritative.

Negative:

- the user's computer must run Bridge Host while the agent is using the phone;
- the current Android WSS transport still needs a trusted reachable device-ingress endpoint;
- local-host packaging and a simpler local device transport remain separate product work.
