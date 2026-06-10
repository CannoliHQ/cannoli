package dev.cannoli.scorza.romm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RommPairingCodeTest {

    @Test fun `normalize uppercases and strips dash and whitespace`() {
        assertEquals("ABCD1234", RommPairingCode.normalize(" abcd-1234 "))
        assertEquals("ABCD1234", RommPairingCode.normalize("ab cd1234"))
    }

    @Test fun `valid only when 8 alphanumeric after normalization`() {
        assertTrue(RommPairingCode.isValid("ABCD-1234"))
        assertTrue(RommPairingCode.isValid("abcd1234"))
        assertFalse(RommPairingCode.isValid("ABCD123"))
        assertFalse(RommPairingCode.isValid("ABCD12345"))
        assertFalse(RommPairingCode.isValid("ABCD-12!"))
        assertFalse(RommPairingCode.isValid(""))
    }
}
