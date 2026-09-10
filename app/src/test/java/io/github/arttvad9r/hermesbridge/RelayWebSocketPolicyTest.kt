package io.github.arttvad9r.hermesbridge

import io.ktor.client.plugins.websocket.WebSockets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayWebSocketPolicyTest {
    @Test
    fun clientFrameLimitMatchesRelayProtocolBound() {
        val config = WebSockets.Config().apply {
            applyRelayWebSocketPolicy()
        }

        assertEquals(256L * 1024L, RELAY_WEBSOCKET_MAX_FRAME_BYTES)
        assertEquals(RELAY_WEBSOCKET_MAX_FRAME_BYTES, config.maxFrameSize)
        assertTrue(config.maxFrameSize < Int.MAX_VALUE.toLong())
    }
}
