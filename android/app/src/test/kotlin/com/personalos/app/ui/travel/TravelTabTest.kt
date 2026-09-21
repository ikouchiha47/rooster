package com.personalos.app.ui.travel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Travel tab strip as data. Two tabs, two different reads: Calendar reads
 * the calendar repository's observances; News reads travel-tagged items. Keeping
 * that mapping here means the screen has no special case of its own to get wrong.
 */
class TravelTabTest {
    @Test
    fun `the strip reads in the order it is shown`() {
        assertEquals(listOf("Calendar", "News"), TravelTab.entries.map { it.label })
    }

    @Test
    fun `each tab loads exactly one thing`() {
        assertTrue(TravelTab.CALENDAR.loadsObservances())
        assertFalse(TravelTab.CALENDAR.loadsTravelNews())

        assertTrue(TravelTab.NEWS.loadsTravelNews())
        assertFalse(TravelTab.NEWS.loadsObservances())
    }

    @Test
    fun `a tab index outside the strip reads as Calendar rather than crashing`() {
        assertTrue(travelTabAt(-1) == TravelTab.CALENDAR)
        assertTrue(travelTabAt(99) == TravelTab.CALENDAR)
        assertTrue(travelTabAt(1) == TravelTab.NEWS)
    }
}
