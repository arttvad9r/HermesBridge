# Relay deployment bundle

The GitHub Actions `hermes-bridge-relay` artifact contains the relay distribution plus these deployment files.

## Quick install or upgrade

On a systemd-based Linux VPS, keep the extracted files together and run:

```bash
sudo bash install-relay.sh relay-*.tar
```

The installer:

- validates that the tar uses the expected `relay/` root and contains `relay/bin/relay`;
- creates the dedicated `hermes-bridge` system user/group when needed;
- preserves `/var/lib/hermes-bridge` across upgrades;
- creates `/etc/hermes-bridge/relay.env` only on first install and does not rotate an existing admin token;
- installs the hardened systemd service;
- replaces the relay distribution and explicitly restarts the already-running service;
- rolls the relay binary directory back if the new service cannot start or remain active.

The installer deliberately does **not** configure DNS, TLS, Caddy/Nginx, firewall rules, Hermes MCP registration, or expose port 8080. Those remain explicit deployment choices.

## After installation

Configure a TLS reverse proxy so the phone sees exactly:

```text
wss://YOUR_HOST/ws/device
```

Only `/health`, `/ws/device`, and token-authenticated `/device-artifacts/*` should be public. Keep `/api/v1/*` loopback-only beside Hermes.

Then configure the local Hermes MCP adapter with `HERMES_BRIDGE_RELAY_URL=http://127.0.0.1:8080` and the admin token stored in `/etc/hermes-bridge/relay.env`.

See [`../docs/DEPLOYMENT.md`](../docs/DEPLOYMENT.md) for the full deployment and smoke-test procedure.
