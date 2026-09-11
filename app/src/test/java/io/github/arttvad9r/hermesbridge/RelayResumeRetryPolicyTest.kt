package io.github.arttvad9r.hermesbridge

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayResumeRetryPolicyTest {
    @Test
    fun pairedResumeRetriesWhenSocketClosesBeforeAuthentication() {
        assertTrue(
            shouldRetryInitialAuthentication(
                hasStoredPairing = true,
                pairingCode = null,
                failure = null,
            )
        )
    }

    @Test
    fun pairedResumeRetriesTransientIoFailureBeforeAuthentication() {
        assertTrue(
            shouldRetryInitialAuthentication(
                hasStoredPairing = true,
                pairingCode = null,
                failure = IOException("network unavailable"),
            )
        )
    }

    @Test
    fun firstPairingDoesNotRetryConsumedPairingFlow() {
        assertFalse(
            shouldRetryInitialAuthentication(
                hasStoredPairing = true,
                pairingCode = "ABCD-EFGH",
                failure = IOException("socket closed"),
            )
        )
    }

    @Test
    fun terminalProtocolFailureDoesNotRetry() {
        assertFalse(
            shouldRetryInitialAuthentication(
                hasStoredPairing = true,
                pairingCode = null,
                failure = IllegalStateException("Authentication failed"),
            )
        )
    }

    @Test
    fun missingStoredPairingDoesNotRetry() {
        assertFalse(
            shouldRetryInitialAuthentication(
                hasStoredPairing = false,
                pairingCode = null,
                failure = IOException("network unavailable"),
            )
        )
    }

    @Test
    fun reconnectBackoffIsBounded() {
        assertEquals(2_000L, nextRelayReconnectBackoffMillis(1_000L))
        assertEquals(MAX_RELAY_RECONNECT_BACKOFF_MILLIS, nextRelayReconnectBackoffMillis(20_000L))
        assertEquals(
            MAX_RELAY_RECONNECT_BACKOFF_MILLIS,
            nextRelayReconnectBackoffMillis(MAX_RELAY_RECONNECT_BACKOFF_MILLIS),
        )
    }
}
