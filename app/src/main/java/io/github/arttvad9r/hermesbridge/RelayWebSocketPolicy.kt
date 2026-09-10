package io.github.arttvad9r.hermesbridge

import io.ktor.client.plugins.websocket.WebSockets

internal const val RELAY_WEBSOCKET_MAX_FRAME_BYTES: Long = 256L * 1024L

/**
 * Keeps the Android client from accepting arbitrarily large frames from a compromised relay.
 * The value intentionally matches the relay server's WebSocket maxFrameSize.
 */
internal fun WebSockets.Config.applyRelayWebSocketPolicy() {
    maxFrameSize = RELAY_WEBSOCKET_MAX_FRAME_BYTES
}
