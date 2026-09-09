package io.github.arttvad9r.hermesbridge.relay

import org.junit.Assert.assertEquals
import org.junit.Test

class RelayCommandTimeoutTest {
    @Test
    fun installGetsLongerBoundedTimeout() {
        assertEquals(300_000L, commandTimeoutMillis("apps.install"))
    }

    @Test
    fun ordinaryToolsKeepShortTimeout() {
        assertEquals(20_000L, commandTimeoutMillis("device.health"))
        assertEquals(20_000L, commandTimeoutMillis("apps.uninstall"))
        assertEquals(20_000L, commandTimeoutMillis("apps.forceStop"))
    }
}
