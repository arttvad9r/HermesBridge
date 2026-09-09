package io.github.arttvad9r.hermesbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidUserIdTest {
    @Test
    fun derivesPrimaryUserFromApplicationUid() {
        assertEquals(0, androidUserIdFromUid(10_123))
    }

    @Test
    fun derivesSecondaryUserFromApplicationUid() {
        assertEquals(10, androidUserIdFromUid(1_012_345))
    }

    @Test
    fun rejectsNegativeUid() {
        assertTrue(runCatching { androidUserIdFromUid(-1) }.isFailure)
    }
}
