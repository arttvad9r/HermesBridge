# Relay deployment

The relay is intended to run on the same VPS as Hermes. Android connects outbound over WSS. Hermes uses the relay admin API locally over loopback.

## Security boundary

Public internet:

- `GET /health`
- `GET /ws/device` (WebSocket upgrade)

Local VPS only:

- `GET /api/v1/devices`
- `POST /api/v1/pairing-codes`
- `POST /api/v1/devices/{deviceId}/commands`

Do not publish TCP/8080 directly to the internet. Terminate TLS at Caddy/Nginx and firewall the relay port.

## 1. Install the relay distribution

Build with:

```bash
gradle --no-daemon :relay:distZip
```

The distribution is produced at `relay/build/distributions/relay.zip` and is also uploaded by GitHub Actions as the `hermes-bridge-relay` artifact.

On the VPS:

```bash
sudo useradd --system --home /var/lib/hermes-bridge --shell /usr/sbin/nologin hermes-bridge || true
sudo mkdir -p /opt/hermes-bridge /etc/hermes-bridge /var/lib/hermes-bridge
sudo unzip relay.zip -d /opt/hermes-bridge/
sudo chown -R root:root /opt/hermes-bridge/relay
sudo chown -R hermes-bridge:hermes-bridge /var/lib/hermes-bridge
```

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

The persistent device registry is stored under `HERMES_BRIDGE_STATE_DIR`. Backing up this directory preserves the relay-side trusted device public keys.

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
    @bridge_public path /health /ws/device

    handle @bridge_public {
        reverse_proxy 127.0.0.1:8080
    }

    handle {
        respond 404
    }
}
```

Caddy handles WebSocket upgrade automatically. Replace `bridge.example.com` with the real DNS name and keep TCP/8080 blocked from the public internet.

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

Send the current read-only tool:

```bash
DEVICE_ID=device_xxx
curl --fail -X POST \
  -H "Authorization: Bearer $HERMES_BRIDGE_ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  --data '{"tool":"device.health","arguments":{}}' \
  "http://127.0.0.1:8080/api/v1/devices/$DEVICE_ID/commands"
```

## 6. Expected persistence behavior

After the first successful pairing:

1. Android keeps the private EC key in Android Keystore and stores its `deviceId` locally.
2. The relay persists the corresponding public key and device metadata.
3. The Android foreground service reconnects automatically when the socket drops.
4. After a normal reboot, `BOOT_COMPLETED` starts the bridge again for an already paired device.
5. Restarting the relay does not require pairing again as long as its state directory is preserved.

## Current limitations

- Only `device.health` is currently allowlisted on Android.
- No Shizuku privileges are enabled yet.
- No package/file mutation tools are enabled yet.
- The admin API is bearer-token authenticated but has no multi-user authorization model; keep it loopback-only.
- A physical-device end-to-end test is still required before treating the bridge as production-ready.
