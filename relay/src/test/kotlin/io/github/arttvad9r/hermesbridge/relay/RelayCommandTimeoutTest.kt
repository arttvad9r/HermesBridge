package io.github.arttvad9r.hermesbridge.relay

import org.junit.Assert.assertEquals
import org.junit.Test

class RelayCommandTimeoutTest {
    @Test
    fun installGetsLongerBoundedTimeout() {
        assertEquals(300_000L, commandTimeoutMillis("apps.install"))
    }

    @Test
    fun boundedReadAnalysisToolsGetExtendedTimeout() {
        assertEquals(60_000L, commandTimeoutMillis("files.analyze"))
        assertEquals(60_000L, commandTimeoutMillis("battery.usage"))
    }

    @Test
    fun ordinaryToolsKeepShortTimeout() {
        assertEquals(20_000L, commandTimeoutMillis("device.health"))
        assertEquals(20_000L, commandTimeoutMillis("files.delete"))
        assertEquals(20_000L, commandTimeoutMillis("apps.uninstall"))
        assertEquals(20_000L, commandTimeoutMillis("apps.forceStop"))
        assertEquals(20_000L, commandTimeoutMillis("apps.revokePermission"))
    }
}
