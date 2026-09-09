package io.github.arttvad9r.hermesbridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolMessagesTest {
    @Test
    fun envelopeRoundTripPreservesTypedPayload() {
        val source = BridgeProtocol.envelope(
            type = MessageType.SESSION_HELLO,
            deviceId = "device_123",
            payload = BridgeProtocol.payload(SessionHelloPayload("device_123")),
        )

        val decoded = BridgeProtocol.decode(BridgeProtocol.encode(source))
        val payload = BridgeProtocol.decodePayload<SessionHelloPayload>(decoded)

        assertEquals(PROTOCOL_VERSION, decoded.v)
        assertEquals("device_123", decoded.deviceId)
        assertEquals("device_123", payload.deviceId)
    }

    @Test
    fun signingBytesAreStable() {
        assertEquals(
            "1\ndevice_1\nchallenge",
            BridgeProtocol.authSigningBytes("device_1", "challenge").decodeToString(),
        )
    }
}
