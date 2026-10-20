package com.personalos.app.ui.tile

import com.personalos.app.data.SourceCount
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceFilterTest {
    @Test
    fun `the all chip leads and counts every family`() {
        val options =
            sourceFilterOptions(
                families = listOf(SourceCount("rss:", 4), SourceCount("gnews:", 2), SourceCount("sms", 1)),
                allCount = 7,
            )
        assertEquals(listOf<String?>(null, "rss:", "gnews:", "sms"), options.map { it.value })
        assertEquals(listOf(7, 4, 2, 1), options.map { it.count })
    }

    @Test
    fun `known families get readable labels and unknown ones fall back`() {
        assertEquals("RSS", sourceFamilyLabel("rss:"))
        assertEquals("Your RSS", sourceFamilyLabel("userrss:"))
        assertEquals("Google News", sourceFamilyLabel("gnews:"))
        assertEquals("SMS", sourceFamilyLabel("sms"))
        assertEquals("calendar", sourceFamilyLabel("calendar:"))
    }
}
