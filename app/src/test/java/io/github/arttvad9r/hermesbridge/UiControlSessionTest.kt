package io.github.arttvad9r.hermesbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiControlSessionTest {
    @Test
    fun leaseIsValidOnlyInsideItsExactGenerationAndDeadline() {
        val state = newActiveUiControlSessionState(
            nowElapsedRealtimeMillis = 1_000L,
            generation = 7L,
            durationMillis = 2_000L,
        )
        val leaseOrNull = uiControlSessionLease(state, 1_000L)
        assertNotNull(leaseOrNull)
        val lease = leaseOrNull!!

        assertEquals(7L, lease.generation)
        assertEquals(3_000L, lease.expiresAtElapsedRealtimeMillis)
        assertTrue(isUiControlSessionLeaseActive(state, lease, 2_999L))
        assertFalse(isUiControlSessionLeaseActive(state, lease, 3_000L))
        assertNull(uiControlSessionLease(state, 999L))
        assertNull(uiControlSessionLease(state, 3_000L))

        val nextGeneration = state.copy(generation = 8L)
        assertFalse(isUiControlSessionLeaseActive(nextGeneration, lease, 2_000L))
    }

    @Test
    fun stoppedOrMalformedLifecycleStateFailsClosed() {
        val stopped = UiControlSessionState(
            status = UiControlSessionStatus.STOPPED,
            generation = 4L,
        )
        assertNull(uiControlSessionLease(stopped, 1_500L))

        assertNull(
            uiControlSessionLease(
                UiControlSessionState(
                    status = UiControlSessionStatus.ACTIVE,
                    generation = 4L,
                    startedAtElapsedRealtimeMillis = null,
                    expiresAtElapsedRealtimeMillis = 2_000L,
                ),
                1_500L,
            )
        )
        assertNull(
            uiControlSessionLease(
                UiControlSessionState(
                    status = UiControlSessionStatus.ACTIVE,
                    generation = 0L,
                    startedAtElapsedRealtimeMillis = 1_000L,
                    expiresAtElapsedRealtimeMillis = 2_000L,
                ),
                1_500L,
            )
        )
        assertNull(uiControlSessionLease(stopped, -1L))
    }

    @Test
    fun activeSessionDurationIsStrictlyBounded() {
        val state = newActiveUiControlSessionState(
            nowElapsedRealtimeMillis = 10_000L,
            generation = 1L,
        )
        assertEquals(
            10_000L + UI_CONTROL_SESSION_MAX_DURATION_MILLIS,
            state.expiresAtElapsedRealtimeMillis,
        )

        assertTrue(
            runCatching {
                newActiveUiControlSessionState(
                    nowElapsedRealtimeMillis = 0L,
                    generation = 1L,
                    durationMillis = 0L,
                )
            }.isFailure
        )
        assertTrue(
            runCatching {
                newActiveUiControlSessionState(
                    nowElapsedRealtimeMillis = 0L,
                    generation = 1L,
                    durationMillis = UI_CONTROL_SESSION_MAX_DURATION_MILLIS + 1L,
                )
            }.isFailure
        )
        assertTrue(
            runCatching {
                newActiveUiControlSessionState(
                    nowElapsedRealtimeMillis = Long.MAX_VALUE,
                    generation = 1L,
                    durationMillis = 1L,
                )
            }.isFailure
        )
        assertTrue(
            runCatching {
                newActiveUiControlSessionState(
                    nowElapsedRealtimeMillis = 0L,
                    generation = 0L,
                )
            }.isFailure
        )
    }

    @Test
    fun leaseCarriesOnlyMonotonicLifecycleAuthorizationMetadata() {
        val lease = UiControlSessionLease(
            generation = 3L,
            expiresAtElapsedRealtimeMillis = 5_000L,
        )
        assertEquals(3L, lease.generation)
        assertEquals(5_000L, lease.expiresAtElapsedRealtimeMillis)
        assertEquals(
            setOf("generation", "expiresAtElapsedRealtimeMillis"),
            UiControlSessionLease::class.java.declaredFields
                .map { it.name }
                .filterNot { it.startsWith("$") }
                .toSet(),
        )
    }
}
