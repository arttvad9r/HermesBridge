package io.github.arttvad9r.hermesbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayEndpointPolicyTest {
    @Test
    fun validRelayProducesCanonicalWebSocketAndArtifactUrls() {
        val endpoint = parseRelayEndpoint("wss://bridge.example.com:8443/ws/device")

        assertEquals("wss://bridge.example.com:8443/ws/device", endpoint.webSocketUrl)
        assertEquals("https://bridge.example.com:8443/device-artifacts", endpoint.artifactBaseUrl)
    }

    @Test
    fun defaultPortAndIpv6AreSupported() {
        assertEquals(
            "https://bridge.example.com/device-artifacts",
            parseRelayEndpoint("wss://bridge.example.com/ws/device").artifactBaseUrl,
        )
        assertEquals(
            "wss://[2001:db8::1]/ws/device",
            parseRelayEndpoint("wss://[2001:db8::1]/ws/device").webSocketUrl,
        )
    }

    @Test
    fun insecureOrAmbiguousRelayUrlsAreRejected() {
        val invalid = listOf(
            "ws://bridge.example.com/ws/device",
            "https://bridge.example.com/ws/device",
            "wss://bridge.example.com/",
            "wss://bridge.example.com/ws/device?token=secret",
            "wss://bridge.example.com/ws/device#fragment",
            "wss://user:pass@bridge.example.com/ws/device",
            "wss://bridge.example.com/ws%2Fdevice",
            "wss:///ws/device",
        )

        invalid.forEach { value ->
            assertTrue("Expected rejection for $value", runCatching { parseRelayEndpoint(value) }.isFailure)
        }
    }
}
