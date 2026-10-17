package io.github.arttvad9r.typeddroid

import io.github.arttvad9r.typeddroid.protocol.TransportEndpointHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayTransportEndpointsTest {
    @Test
    fun `direct hints are preferred before configured relay`() {
        val now = 1_000L
        val direct = TransportEndpointHint(
            webSocketUrl = "wss://192.168.1.10:8443/ws/device",
            certificateSha256 = "a".repeat(64),
            expiresAtEpochMillis = now + 60_000L,
        )
        val candidates = relayConnectionCandidates(
            "wss://bridge.example.com/ws/device",
            listOf(direct),
            now,
        )
        assertEquals(2, candidates.size)
        assertEquals(RelayEndpointSource.DIRECT, candidates[0].source)
        assertEquals("192.168.1.10", java.net.URI(candidates[0].endpoint.webSocketUrl).host)
        assertEquals(RelayEndpointSource.CONFIGURED, candidates[1].source)
    }

    @Test
    fun `direct hints require private literal address valid pin and future expiry`() {
        val now = 5_000L
        fun hint(host: String, pin: String = "b".repeat(64), expires: Long = now + 1_000) =
            TransportEndpointHint("wss://$host:8443/ws/device", pin, expires)

        assertTrue(validateDirectTransportHint(hint("10.0.2.2"), now) != null)
        assertTrue(validateDirectTransportHint(hint("172.16.1.1"), now) != null)
        assertTrue(validateDirectTransportHint(hint("192.168.20.2"), now) != null)
        assertTrue(validateDirectTransportHint(hint("100.64.1.2"), now) != null)
        assertNull(validateDirectTransportHint(hint("8.8.8.8"), now))
        assertNull(validateDirectTransportHint(hint("example.com"), now))
        assertNull(validateDirectTransportHint(hint("10.0.2.2", "bad"), now))
        assertNull(validateDirectTransportHint(hint("10.0.2.2", expires = now), now))
    }

    @Test
    fun `direct literal classifier rejects loopback and public addresses`() {
        assertFalse(isAllowedDirectLiteralAddress("127.0.0.1"))
        assertFalse(isAllowedDirectLiteralAddress("8.8.8.8"))
        assertFalse(isAllowedDirectLiteralAddress("example.com"))
        assertTrue(isAllowedDirectLiteralAddress("192.168.0.7"))
        assertTrue(isAllowedDirectLiteralAddress("100.100.10.20"))
        assertTrue(isAllowedDirectLiteralAddress("fd00::1234"))
        assertFalse(isAllowedDirectLiteralAddress("fe80::1234"))
    }
}
