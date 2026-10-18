package com.personalos.app.data.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The pure half of contact resolution: the matcher and the key it compares on.
 * No Android types here, so the rule is exercised on the JVM.
 */
class ContactNamesTest {
    private val byNumber =
        mapOf(
            "9876543210" to "Asha Menon",
            "9123456789" to "Bank Alerts",
        )

    @Test
    fun `country code, trunk zero and spaces are the same contact`() {
        assertEquals("Asha Menon", contactNameFor("+919876543210", byNumber))
        assertEquals("Asha Menon", contactNameFor("919876543210", byNumber))
        assertEquals("Asha Menon", contactNameFor("09876543210", byNumber))
        assertEquals("Asha Menon", contactNameFor("9876543210", byNumber))
        assertEquals("Asha Menon", contactNameFor("+91 98765 43210", byNumber))
    }

    @Test
    fun `the key is the last ten digits`() {
        assertEquals("9876543210", senderMatchKey("+91 98765 43210"))
        assertEquals("9876543210", senderMatchKey("09876543210"))
        assertEquals("9876543210", senderMatchKey("9876543210"))
    }

    @Test
    fun `a DLT business header never resolves`() {
        assertNull(contactNameFor("VM-HDFCBK-S", byNumber))
        assertNull(contactNameFor("AX-FLPKRT-S", byNumber))
        assertNull(contactNameFor("JD-SBIINB", byNumber))
    }

    @Test
    fun `a short code never resolves`() {
        assertNull(contactNameFor("56767", byNumber))
        assertNull(contactNameFor("1900", byNumber))
        // A four-digit slice of a real number must not collide with it.
        assertNull(contactNameFor("3210", byNumber))
    }

    @Test
    fun `an unknown number never resolves`() {
        assertNull(contactNameFor("+918888888888", byNumber))
    }

    @Test
    fun `a null or empty sender never resolves`() {
        assertNull(contactNameFor(null, byNumber))
        assertNull(contactNameFor("", byNumber))
        assertNull(senderMatchKey(null))
        assertNull(senderMatchKey(""))
    }
}
