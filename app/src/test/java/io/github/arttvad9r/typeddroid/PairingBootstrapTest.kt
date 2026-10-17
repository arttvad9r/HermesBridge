package io.github.arttvad9r.typeddroid

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingBootstrapTest {
    private val now = 1_700_000_000_000L
    private val pin = "ab".repeat(32)

    @Test
    fun `plain legacy pairing code remains accepted`() {
        val parsed = parsePairingBootstrapInput("abcd-2345", now)
        assertEquals("ABCD-2345", parsed?.code)
        assertTrue(parsed?.endpoints?.isEmpty() == true)
    }

    @Test
    fun `bundle accepts pinned private and tailscale endpoints`() {
        val bundle = bundle(
            """{"v":1,"code":"ABCD-2345","expiresAtEpochMillis":${now + 300_000},"endpoints":[{"webSocketUrl":"wss://192.168.3.4:8443/ws/device","certificateSha256":"$pin"},{"webSocketUrl":"wss://100.106.23.9:8443/ws/device","certificateSha256":"$pin"}]}"""
        )
        val parsed = requireNotNull(parsePairingBootstrapInput(bundle, now))
        assertEquals("ABCD-2345", parsed.code)
        assertEquals(2, parsed.endpoints.size)
    }

    @Test
    fun `bundle rejects public endpoint wrong pin and expired code`() {
        val public = bundle(
            """{"v":1,"code":"ABCD-2345","expiresAtEpochMillis":${now + 300_000},"endpoints":[{"webSocketUrl":"wss://8.8.8.8:8443/ws/device","certificateSha256":"$pin"}]}"""
        )
        val badPin = bundle(
            """{"v":1,"code":"ABCD-2345","expiresAtEpochMillis":${now + 300_000},"endpoints":[{"webSocketUrl":"wss://192.168.3.4:8443/ws/device","certificateSha256":"00"}]}"""
        )
        val expired = bundle(
            """{"v":1,"code":"ABCD-2345","expiresAtEpochMillis":${now - 1},"endpoints":[{"webSocketUrl":"wss://192.168.3.4:8443/ws/device","certificateSha256":"$pin"}]}"""
        )
        assertNull(parsePairingBootstrapInput(public, now))
        assertNull(parsePairingBootstrapInput(badPin, now))
        assertNull(parsePairingBootstrapInput(expired, now))
    }

    private fun bundle(json: String): String {
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(json.encodeToByteArray())
        return PAIRING_BUNDLE_PREFIX + encoded
    }
}
