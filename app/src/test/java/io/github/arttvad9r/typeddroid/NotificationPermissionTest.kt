package io.github.arttvad9r.typeddroid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionTest {
    @Test
    fun notificationPermissionIsRuntimeOnlyFromAndroid13() {
        assertFalse(requiresRuntimePermission(32))
        assertTrue(requiresRuntimePermission(33))
        assertTrue(requiresRuntimePermission(35))
    }
}
