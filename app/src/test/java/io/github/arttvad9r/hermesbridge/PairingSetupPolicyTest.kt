package io.github.arttvad9r.hermesbridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingSetupPolicyTest {
    @Test
    fun completedSetupIsKeptWhilePairingExists() {
        assertTrue(
            reconciledSetupCompletion(
                currentSetupCompleted = true,
                hasStoredPairing = true,
            )
        )
    }

    @Test
    fun completedSetupIsClearedOnlyAfterPairingIsGone() {
        assertFalse(
            reconciledSetupCompletion(
                currentSetupCompleted = true,
                hasStoredPairing = false,
            )
        )
    }

    @Test
    fun incompleteSetupNeverBecomesCompletedDuringReconciliation() {
        assertFalse(
            reconciledSetupCompletion(
                currentSetupCompleted = false,
                hasStoredPairing = true,
            )
        )
        assertFalse(
            reconciledSetupCompletion(
                currentSetupCompleted = false,
                hasStoredPairing = false,
            )
        )
    }
}
