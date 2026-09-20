package com.personalos.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class CompactNumberTest {
    @Test
    fun `below a thousand stays exact`() {
        assertEquals("0", compactNumber(0))
        assertEquals("1", compactNumber(1))
        assertEquals("999", compactNumber(999))
    }

    @Test
    fun `thousands read with two decimals`() {
        assertEquals("1.00K", compactNumber(1_000))
        assertEquals("1.50K", compactNumber(1_500))
        assertEquals("999.99K", compactNumber(999_990))
    }

    @Test
    fun `millions and billions roll over`() {
        assertEquals("1.00M", compactNumber(1_000_000))
        assertEquals("2.40M", compactNumber(2_400_000))
        assertEquals("1.00B", compactNumber(1_000_000_000))
    }

    @Test
    fun `a negative count keeps its sign`() {
        assertEquals("-1.50K", compactNumber(-1_500))
    }
}
