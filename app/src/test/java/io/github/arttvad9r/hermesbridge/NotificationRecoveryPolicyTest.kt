package io.github.arttvad9r.hermesbridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRecoveryPolicyTest {
    @Test
    fun notificationPermissionIsRuntimeOnlyFromAndroid13() {
        assertFalse(requiresRuntimePermission(32))
        assertTrue(requiresRuntimePermission(33))
        assertTrue(requiresRuntimePermission(35))
    }

    @Test
    fun ShizukuRestoreNoticeRequiresPriorConfigurationAndNotifications() {
        assertFalse(
            shouldNotifyShizukuRestoration(
                wasConfigured = false,
                status = ShizukuAccessStatus.UNAVAILABLE,
                notificationsGranted = true,
            )
        )
        assertFalse(
            shouldNotifyShizukuRestoration(
                wasConfigured = true,
                status = ShizukuAccessStatus.UNAVAILABLE,
                notificationsGranted = false,
            )
        )
        assertTrue(
            shouldNotifyShizukuRestoration(
                wasConfigured = true,
                status = ShizukuAccessStatus.UNAVAILABLE,
                notificationsGranted = true,
            )
        )
    }

    @Test
    fun readyShizukuNeverNeedsRestorationNotice() {
        assertFalse(
            shouldNotifyShizukuRestoration(
                wasConfigured = true,
                status = ShizukuAccessStatus.READY,
                notificationsGranted = true,
            )
        )
    }
}
