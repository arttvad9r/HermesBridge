package io.github.arttvad9r.hermesbridge.pairing

import io.github.arttvad9r.hermesbridge.PairingCodeValidator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCodeValidatorTest {
    @Test
    fun acceptsExpectedFormat() {
        assertTrue(PairingCodeValidator.isValid("A1B2-C3D4"))
    }

    @Test
    fun normalizesCaseAndWhitespace() {
        assertTrue(PairingCodeValidator.isValid("  a1b2-c3d4  "))
    }

    @Test
    fun rejectsShortOrUnseparatedCodes() {
        assertFalse(PairingCodeValidator.isValid("ABCD"))
        assertFalse(PairingCodeValidator.isValid("ABCD1234"))
    }
}
