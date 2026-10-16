package io.github.arttvad9r.typeddroid.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbConnectionBannerTest {
    @Test
    fun hostBannerMatchesCurrentAospWireFormat() {
        val banner = buildHostConnectBanner(setOf("shell_v2", "cmd", "abb_exec"))

        assertEquals(
            "host::features=abb_exec,cmd,shell_v2;",
            banner.decodeToString(),
        )
    }

    @Test
    fun emptyHostFeatureSetProducesMinimalBanner() {
        assertEquals("host::", buildHostConnectBanner(emptySet()).decodeToString())
    }

    @Test
    fun deviceBannerParsesIdentityAndFeatures() {
        val banner = (
            "device::ro.product.name=komodo;" +
                "ro.product.model=Pixel 9 Pro XL;" +
                "ro.product.device=komodo;" +
                "features=shell_v2,cmd,abb_exec;\u0000"
            ).encodeToByteArray()

        val parsed = parseDeviceConnectBanner(banner)

        assertEquals("komodo", parsed.productName)
        assertEquals("Pixel 9 Pro XL", parsed.productModel)
        assertEquals("komodo", parsed.productDevice)
        assertEquals(setOf("shell_v2", "cmd", "abb_exec"), parsed.features)
    }

    @Test
    fun absentIdentityPropertiesRemainOptional() {
        val parsed = parseDeviceConnectBanner("device::features=shell_v2;\u0000".encodeToByteArray())

        assertNull(parsed.productModel)
        assertTrue("shell_v2" in parsed.features)
    }

    @Test
    fun malformedBannerIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            parseDeviceConnectBanner("host::features=shell_v2;\u0000".encodeToByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseDeviceConnectBanner("device::features=shell_v2;\u0000evil".encodeToByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseDeviceConnectBanner("device::features=shell_v2;features=cmd;\u0000".encodeToByteArray())
        }
    }

    @Test
    fun unsafeHostFeatureTokenIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            buildHostConnectBanner(setOf("shell_v2;evil=x"))
        }
    }

    @Test
    fun `device banner accepts current AOSP non nul payload`() {
        val parsed = parseDeviceConnectBanner(
            "device::ro.product.model=Pixel;features=shell_v2,cmd;".encodeToByteArray()
        )
        assertEquals("Pixel", parsed.productModel)
        assertTrue("shell_v2" in parsed.features)
    }
}
