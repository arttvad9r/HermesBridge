package io.github.arttvad9r.typeddroid

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class EmbeddedPrivilegedProtocolTest {
    @Test
    fun `request frame round trips`() {
        val request = EmbeddedPrivilegedRequest(
            id = "request-1",
            operation = EmbeddedPrivilegedProtocol.OP_EXEC,
            command = "id; getprop ro.build.version.release",
            timeoutMillis = 12_345,
        )
        val bytes = ByteArrayOutputStream().also {
            EmbeddedPrivilegedProtocol.writeRequest(it, request)
        }.toByteArray()

        assertEquals(request, EmbeddedPrivilegedProtocol.readRequest(ByteArrayInputStream(bytes)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `oversized incoming frame is rejected before allocation`() {
        val bytes = byteArrayOf(0x7f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte())
        EmbeddedPrivilegedProtocol.readRequest(ByteArrayInputStream(bytes))
    }
    @Test
    fun `typed privileged request fields round trip`() {
        val request = EmbeddedPrivilegedRequest(
            id = "typed-1",
            operation = EmbeddedPrivilegedProtocol.OP_INSTALL_APK,
            packageName = "com.example.app",
            permissionName = "android.permission.CAMERA",
            userId = 10,
            keepData = true,
            artifactSizeBytes = 123456,
            artifactSha256 = "a".repeat(64),
            replace = false,
        )
        val bytes = ByteArrayOutputStream().also {
            EmbeddedPrivilegedProtocol.writeRequest(it, request)
        }.toByteArray()

        assertEquals(request, EmbeddedPrivilegedProtocol.readRequest(ByteArrayInputStream(bytes)))
    }

}
