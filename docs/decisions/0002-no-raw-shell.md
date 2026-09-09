# ADR 0002: Do not expose a raw shell to Hermes

Status: Accepted

## Context

Shizuku and ADB make it easy to offer a generic shell command. That maximizes capability but also turns prompt injection, agent mistakes and relay compromise into broad arbitrary-command execution on a personal phone.

## Decision

Hermes Bridge will expose typed tools only. A generic `run_shell` tool is forbidden from the agent-facing registry in V1.

If an implementation internally needs a shell command, it must be encapsulated by a dedicated operation with:

- fixed executable/operation;
- validated arguments;
- bounded output;
- risk classification;
- approval policy;
- audit entry.

## Consequences

Some new features take more implementation work, but the authority granted to the agent remains reviewable and understandable.
