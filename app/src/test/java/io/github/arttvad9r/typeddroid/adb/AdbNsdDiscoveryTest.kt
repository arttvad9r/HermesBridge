package io.github.arttvad9r.typeddroid.adb

import org.junit.Assert.assertEquals
import org.junit.Test

class AdbNsdDiscoveryTest {
    @Test
    fun `service type normalization ignores trailing dot and case`() {
        assertEquals(
            normalizeServiceType("_adb-tls-pairing._tcp."),
            normalizeServiceType("_ADB-TLS-PAIRING._TCP"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `endpoint rejects invalid port`() {
        AdbTcpEndpoint("127.0.0.1", 0)
    }
}
