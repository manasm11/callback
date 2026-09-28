package com.shopcallback.tracker.util

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneNumberNormalizerTest {
    @Test
    fun `strips spaces and dashes`() {
        assertEquals("9876543210", PhoneNumberNormalizer.normalize("98765-43210"))
    }

    @Test
    fun `strips plus and country code`() {
        assertEquals("9876543210", PhoneNumberNormalizer.normalize("+91 98765 43210"))
    }

    @Test
    fun `formatted and plain numbers normalize the same`() {
        assertEquals(
            PhoneNumberNormalizer.normalize("+91 98765 43210"),
            PhoneNumberNormalizer.normalize("9876543210")
        )
    }

    @Test
    fun `short numbers are kept as-is`() {
        assertEquals("12345", PhoneNumberNormalizer.normalize("123-45"))
    }
}
