package io.github.arttvad9r.typeddroid.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedAdbBootstrapTest {
    @Test
    fun `bootstrap command starts owned app process detached from adb shell`() {
        val command = buildBootstrapCommand(
            apkPath = "/data/app/~~abc/io.github.arttvad9r.typeddroid-xyz/base.apk",
            socketName = "typeddroid-0123456789abcdef0123456789abcdef",
            appUid = 10234,
        )

        assertTrue(command.startsWith("CLASSPATH='/data/app/"))
        assertTrue(command.contains("/system/bin/app_process /system/bin io.github.arttvad9r.typeddroid.EmbeddedPrivilegedMain"))
        assertTrue(command.contains("--socket 'typeddroid-0123456789abcdef0123456789abcdef'"))
        assertTrue(command.endsWith("--app-uid 10234 </dev/null >/dev/null 2>&1 &"))
    }

    @Test
    fun `shell quote safely handles apostrophe`() {
        assertEquals("'a'\"'\"'b'", shellQuote("a'b"))
    }
}
