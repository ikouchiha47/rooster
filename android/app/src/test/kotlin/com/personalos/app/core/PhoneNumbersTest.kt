package com.personalos.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneNumbersTest {
    @Test
    fun `the same number in every shape becomes one identity`() {
        val forms = listOf("+91 98765 43210", "+919876543210", "09876543210", "9876543210", "919876543210")
        val canon = forms.map { PhoneNumbers.canonical(it, "IN") }.distinct()
        assertEquals(listOf("+919876543210"), canon)
    }

    @Test
    fun `canonicalising is idempotent`() {
        val once = PhoneNumbers.canonical("9876543210", "IN")
        assertEquals(once, PhoneNumbers.canonical(once, "IN"))
    }

    @Test
    fun `a business header is left alone`() {
        assertEquals("VM-HDFCBK-S", PhoneNumbers.canonical("VM-HDFCBK-S", "IN"))
    }

    @Test
    fun `a short code is left alone`() {
        assertEquals("56767", PhoneNumbers.canonical("56767", "IN"))
    }

    @Test
    fun `an unknown country leaves the digits as they are`() {
        assertEquals("9876543210", PhoneNumbers.canonical("9876543210", "ZZ"))
    }

    @Test
    fun `another country gets its own code`() {
        assertEquals("+14155552671", PhoneNumbers.canonical("(415) 555-2671", "US"))
    }
}
