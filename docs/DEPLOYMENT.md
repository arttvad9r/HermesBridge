# Relay deployment

The relay is intended to run on the same VPS as Hermes. Android connects outbound over WSS. Hermes uses the relay admin API locally over loopback.

## Security boundary

Public internet:

- `GET /health`
- `GET /ws/device` (WebSocket upgrade)
- `GET /device-artifacts/{artifactId}` — short-lived APK download; requires the per-artifact bearer token

Local VPS only:

- `GET /api/v1/devices`
- `POST /api/v1/pairing-codes`
- `POST /api/v1/apk-artifacts` — streams an APK into the short-lived relay staging store
- `POST /api/v1/devices/{deviceId}/commands`

Do not publish TCP/8080 directly to the internet. Terminate TLS at Caddy/Nginx and firewall the relay port. In particular, never proxy `/api/v1/*` from the public internet.

## 1. Install the relay distribution

Build with:

```bash
gradle --no-daemon :relay:distTar
```

The distribution is produced under `relay/build/distributions/` and is also uploaded by GitHub Actions as the `hermes-bridge-relay` artifact.

On the VPS, extract the distribution under `/opt/hermes-bridge` and keep mutable state outside the application directory:

```bash
sudo useradd --system --home /var/lib/hermes-bridge --shell /usr/sbin/nologin hermes-bridge || true
sudo mkdir -p /opt/hermes-bridge /etc/hermes-bridge /var/lib/hermes-bridge
sudo tar -xf relay.tar -C /opt/hermes-bridge/
sudo chown -R root:root /opt/hermes-bridge/relay
sudo chown -R hermes-bridge:hermes-bridge /var/lib/hermes-bridge
```

Adjust the archive filename to the one produced by the build.

## 2. Configure secrets

Copy `deploy/relay.env.example` to `/etc/hermes-bridge/relay.env`.

Generate the admin token locally on the VPS:

```bash
openssl rand -hex 32
```

Set:

```text
HERMES_BRIDGE_ADMIN_TOKEN=<generated secret>
HERMES_BRIDGE_STATE_DIR=/var/lib/hermes-bridge
PORT=8080
```

Then protect the file:

```bash
sudo chown root:hermes-bridge /etc/hermes-bridge/relay.env
sudo chmod 0640 /etc/hermes-bridge/relay.env
```

The persistent device registry is stored under `HERMES_BRIDGE_STATE_DIR`. Short-lived APK artifacts are staged under `HERMES_BRIDGE_STATE_DIR/apk-artifacts` and are removed when their TTL expires.

## 3. Install systemd service

```bash
sudo cp deploy/hermes-bridge-relay.service /etc/systemd/system/hermes-bridge-relay.service
sudo systemctl daemon-reload
sudo systemctl enable --now hermes-bridge-relay
sudo systemctl status hermes-bridge-relay
```

Local health check:

```bash
curl --fail http://127.0.0.1:8080/health
```

## 4. TLS reverse proxy

Example Caddy configuration:

```caddyfile
bridge.example.com {
    @bridge_public path /health /ws/device /device-artifacts/*

    handle @bridge_public {
        reverse_proxy 127.0.0.1:8080
    }

    handle {
        respond 404
    }
}
```

Caddy handles WebSocket upgrade automatically. Replace `bridge.example.com` with the real DNS name and keep TCP/8080 blocked from the public internet.

`/device-artifacts/*` must be public at the routing layer so the phone can download a staged APK, but each artifact still requires its own high-entropy short-lived bearer token. The admin staging endpoint `/api/v1/apk-artifacts` must remain loopback-only.

The Android build must use:

```text
wss://bridge.example.com/ws/device
```

Build example:

```bash
gradle --no-daemon \
  -PHERMES_BRIDGE_RELAY_WS_URL=wss://bridge.example.com/ws/device \
  :app:assembleDebug
```

## 5. Admin API smoke test

Load the token without printing it:

```bash
set -a
source /etc/hermes-bridge/relay.env
set +a
```

Create a one-time pairing code:

```bash
curl --fail -X POST \
  -H "Authorization: Bearer $HERMES_BRIDGE_ADMIN_TOKEN" \
  http://127.0.0.1:8080/api/v1/pairing-codes
```

Enter the returned code in the Android app. After successful pairing, list devices:

```bash
curl --fail \
  -H "Authorization: Bearer $HERMES_BRIDGE_ADMIN_TOKEN" \
  http://127.0.0.1:8080/api/v1/devices
```

Send a read-only health command:

```bash
DEVICE_ID=device_xxx
curl --fail -X POST \
  -H "Authorization: Bearer $HERMES_BRIDGE_ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  --data '{"tool":"device.health","arguments":{}}' \
  "http://127.0.0.1:8080/api/v1/devices/$DEVICE_ID/commands"
```

The relay also transports the other typed tools, but Hermes should normally reach them through the MCP adapter rather than by hand-crafting relay commands.

## 6. Hermes MCP adapter

Install `hermes_mcp/` into a dedicated Python environment on the same VPS. Configure:

```text
HERMES_BRIDGE_RELAY_URL=http://127.0.0.1:8080
HERMES_BRIDGE_ADMIN_TOKEN=<same admin token>
HERMES_BRIDGE_APK_DIR=/opt/HermesBridge/apks
```

Create the APK staging directory with permissions appropriate for the account running Hermes/MCP. Only files directly inside this directory can be selected by the `install_apk` MCP tool; arbitrary VPS paths are rejected.

Because the relay URL is loopback, HTTP is accepted for this internal hop. Non-loopback MCP relay URLs must use HTTPS.

See [HERMES_MCP.md](HERMES_MCP.md) for the current typed tool surface and Hermes registration flow.

## 7. Expected persistence behavior

After the first successful pairing:

1. Android keeps the private EC key in Android Keystore and stores its `deviceId` locally.
2. The relay persists the corresponding public key and device metadata.
3. The Android foreground service reconnects automatically when the socket drops.
4. After a normal reboot, `BOOT_COMPLETED` starts the bridge again for an already paired device.
5. Restarting the relay does not require pairing again as long as its state directory is preserved.
6. If the relay loses its device registry and returns `unknown_device`, Android clears the stale local `deviceId` and allows a new pairing instead of reconnecting forever.

## Current tools

Read-only:

- `device.health`
- `apps.list`
- `files.list` — only after the user grants a SAF directory in the Android app

Privileged/mutating, with Android-side approval:

- `apps.install` — staged APK, verified by size/SHA-256/package/signing metadata before approval
- `apps.uninstall`
- `apps.forceStop`

## Current limitations

- Shizuku must be running and Hermes Bridge must be authorized before privileged package actions can execute.
- Privileged package actions are implemented but still require a physical-device end-to-end validation before they should be treated as production-ready.
- The admin API is bearer-token authenticated but has no multi-user authorization model; keep it loopback-only.
- A physical-device end-to-end test over real mobile/Wi-Fi network transitions is still required before treating the bridge as production-ready.
