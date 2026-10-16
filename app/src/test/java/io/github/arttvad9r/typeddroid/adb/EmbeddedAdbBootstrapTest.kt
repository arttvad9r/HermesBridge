package io.github.arttvad9r.typeddroid.adb

import io.github.arttvad9r.typeddroid.EmbeddedPrivilegedEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedAdbBootstrapTest {
    @Test
    fun `bootstrap command starts owned app process on authenticated loopback`() {
        val endpoint = EmbeddedPrivilegedEndpoint(
            port = 45678,
            authToken = "a".repeat(64),
        )
        val command = buildBootstrapCommand(
            apkPath = "/data/app/~~abc/io.github.arttvad9r.typeddroid-xyz/base.apk",
            endpoint = endpoint,
        )

        assertTrue(command.startsWith("CLASSPATH='/data/app/"))
        assertTrue(command.contains("/system/bin/app_process /system/bin io.github.arttvad9r.typeddroid.EmbeddedPrivilegedMain"))
        assertTrue(command.contains("--port 45678"))
        assertTrue(command.contains("--token '${"a".repeat(64)}'"))
        assertTrue(command.endsWith("</dev/null >/dev/null 2>&1 &"))
    }

    @Test
    fun `shell quote safely handles apostrophe`() {
        assertEquals("'a'\"'\"'b'", shellQuote("a'b"))
    }
}
