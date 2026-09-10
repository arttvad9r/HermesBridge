package io.github.arttvad9r.hermesbridge.protocol

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
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
    fun decodingErrorsDoNotExposeRawInput() {
        val secretMarker = "HERMES_PRIVATE_INPUT_MARKER"
        val malformed = """{"v":"$secretMarker","id":"id","type":"error","timestamp":"2026-01-01T00:00:00Z"}"""

        try {
            BridgeProtocol.decode(malformed)
            fail("Malformed protocol JSON must fail decoding")
        } catch (error: SerializationException) {
            assertFalse(
                "Decoding error message exposed raw untrusted JSON input",
                error.message.orEmpty().contains(secretMarker),
            )
        }
    }

    @Test
    fun signingBytesAreStable() {
        assertEquals(
            "1\ndevice_1\nchallenge",
            BridgeProtocol.authSigningBytes("device_1", "challenge").decodeToString(),
        )
    }
}
