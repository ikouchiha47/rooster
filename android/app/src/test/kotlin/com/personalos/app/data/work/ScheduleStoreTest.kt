package com.personalos.app.data.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleStoreTest {
    @Test
    fun `a deficit map round-trips, family names with colons included`() {
        val deficits = mapOf("sms" to 3, "rss:" to -1, "gnews:" to 0)
        assertEquals(deficits, parseDeficits(encodeDeficits(deficits)))
    }

    @Test
    fun `empty is empty`() {
        assertTrue(parseDeficits("").isEmpty())
    }

    @Test
    fun `a malformed entry is skipped, not fatal`() {
        assertEquals(mapOf("sms" to 2), parseDeficits("sms=2;broken;gnews=x"))
    }
}
