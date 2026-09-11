package io.github.arttvad9r.hermesbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiControlSessionUiPolicyTest {
    @Test
    fun sessionCanStartOnlyWhenEveryVisibilityAndBackendPrerequisiteIsReady() {
        assertTrue(
            canStartUiControlSession(
                sessionStatus = UiControlSessionStatus.STOPPED,
                connectionState = ConnectionState.CONNECTED,
                shizukuStatus = ShizukuAccessStatus.READY,
                notificationVisible = true,
            )
        )
        assertFalse(
            canStartUiControlSession(
                sessionStatus = UiControlSessionStatus.ACTIVE,
                connectionState = ConnectionState.CONNECTED,
                shizukuStatus = ShizukuAccessStatus.READY,
                notificationVisible = true,
            )
        )
        assertFalse(
            canStartUiControlSession(
                sessionStatus = UiControlSessionStatus.STOPPED,
                connectionState = ConnectionState.RECONNECTING,
                shizukuStatus = ShizukuAccessStatus.READY,
                notificationVisible = true,
            )
        )
        assertFalse(
            canStartUiControlSession(
                sessionStatus = UiControlSessionStatus.STOPPED,
                connectionState = ConnectionState.CONNECTED,
                shizukuStatus = ShizukuAccessStatus.UNAVAILABLE,
                notificationVisible = true,
            )
        )
        assertFalse(
            canStartUiControlSession(
                sessionStatus = UiControlSessionStatus.STOPPED,
                connectionState = ConnectionState.CONNECTED,
                shizukuStatus = ShizukuAccessStatus.READY,
                notificationVisible = false,
            )
        )
    }

    @Test
    fun activeSessionStopsAtDeadlineOrWhenAnyPrerequisiteIsLost() {
        val active = newActiveUiControlSessionState(
            nowElapsedRealtimeMillis = 1_000L,
            generation = 2L,
            durationMillis = 4_000L,
        )

        assertNull(
            uiControlSessionStopReason(
                sessionState = active,
                nowElapsedRealtimeMillis = 4_999L,
                connectionState = ConnectionState.CONNECTED,
                shizukuStatus = ShizukuAccessStatus.READY,
                notificationVisible = true,
            )
        )
        assertEquals(
            UiControlSessionStopReason.EXPIRED_OR_INVALID,
            uiControlSessionStopReason(
                sessionState = active,
                nowElapsedRealtimeMillis = 5_000L,
                connectionState = ConnectionState.CONNECTED,
                shizukuStatus = ShizukuAccessStatus.READY,
                notificationVisible = true,
            )
        )
        assertEquals(
            UiControlSessionStopReason.BRIDGE_DISCONNECTED,
            uiControlSessionStopReason(
                sessionState = active,
                nowElapsedRealtimeMillis = 2_000L,
                connectionState = ConnectionState.RECONNECTING,
                shizukuStatus = ShizukuAccessStatus.READY,
                notificationVisible = true,
            )
        )
        assertEquals(
            UiControlSessionStopReason.SHIZUKU_UNAVAILABLE,
            uiControlSessionStopReason(
                sessionState = active,
                nowElapsedRealtimeMillis = 2_000L,
                connectionState = ConnectionState.CONNECTED,
                shizukuStatus = ShizukuAccessStatus.DENIED,
                notificationVisible = true,
            )
        )
        assertEquals(
            UiControlSessionStopReason.NOTIFICATION_VISIBILITY_LOST,
            uiControlSessionStopReason(
                sessionState = active,
                nowElapsedRealtimeMillis = 2_000L,
                connectionState = ConnectionState.CONNECTED,
                shizukuStatus = ShizukuAccessStatus.READY,
                notificationVisible = false,
            )
        )
    }

    @Test
    fun stoppedSessionNeedsNoWatchdogStopReason() {
        assertNull(
            uiControlSessionStopReason(
                sessionState = UiControlSessionState(),
                nowElapsedRealtimeMillis = Long.MAX_VALUE,
                connectionState = ConnectionState.DISCONNECTED,
                shizukuStatus = ShizukuAccessStatus.UNAVAILABLE,
                notificationVisible = false,
            )
        )
    }
}
