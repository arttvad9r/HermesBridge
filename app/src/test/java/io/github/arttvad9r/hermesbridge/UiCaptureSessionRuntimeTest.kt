package io.github.arttvad9r.hermesbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiCaptureSessionRuntimeTest {
    @Test
    fun activeSessionHasBoundedFiveMinuteDeadline() {
        UiCaptureSessionRuntime.stopped()
        UiCaptureSessionRuntime.active(nowEpochMillis = 1_000L)

        val state = UiCaptureSessionRuntime.state.value
        assertEquals(UiCaptureSessionStatus.ACTIVE, state.status)
        assertEquals(1_000L, state.startedAtEpochMillis)
        assertEquals(1_000L + UI_CAPTURE_SESSION_MAX_DURATION_MILLIS, state.expiresAtEpochMillis)
    }

    @Test
    fun runtimeNeverCarriesConsentOrPixelPayloads() {
        UiCaptureSessionRuntime.requestingConsent()
        val state = UiCaptureSessionRuntime.state.value

        assertEquals(UiCaptureSessionStatus.REQUESTING_CONSENT, state.status)
        assertNull(state.startedAtEpochMillis)
        assertNull(state.expiresAtEpochMillis)
    }

    @Test
    fun consentCanOnlyBeRequestedFromInactiveTerminalStates() {
        assertTrue(canRequestUiCaptureConsent(UiCaptureSessionStatus.STOPPED))
        assertTrue(canRequestUiCaptureConsent(UiCaptureSessionStatus.DENIED))
        assertTrue(canRequestUiCaptureConsent(UiCaptureSessionStatus.ERROR))

        assertFalse(canRequestUiCaptureConsent(UiCaptureSessionStatus.REQUESTING_CONSENT))
        assertFalse(canRequestUiCaptureConsent(UiCaptureSessionStatus.STARTING))
        assertFalse(canRequestUiCaptureConsent(UiCaptureSessionStatus.ACTIVE))
    }

    @Test
    fun errorMessageIsBoundedAndControlFree() {
        val bounded = boundedUiCaptureMessage("capture\nfailed\t" + "x".repeat(400))

        assertEquals(MAX_UI_CAPTURE_MESSAGE_CHARS, bounded.length)
        assertFalse(bounded.any(Char::isISOControl))
        assertTrue(bounded.startsWith("capture failed "))
    }

    @Test
    fun customDurationCannotExceedSessionMaximum() {
        assertTrue(
            runCatching {
                UiCaptureSessionRuntime.active(
                    nowEpochMillis = 0L,
                    durationMillis = UI_CAPTURE_SESSION_MAX_DURATION_MILLIS + 1,
                )
            }.isFailure
        )
    }
}
