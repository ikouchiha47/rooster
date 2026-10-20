package com.personalos.app.ui.travel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Travel tab strip as data. Three tabs, three different reads: Holidays
 * reads the calendar repository's observances; News reads travel-tagged items;
 * Alerts reads the rule matches scoped to those same travel facts. Keeping that
 * mapping here means the screen has no special case of its own to get wrong.
 */
class TravelTabTest {
    @Test
    fun `the strip reads in the order it is shown`() {
        assertEquals(listOf("Holidays", "News", "Alerts"), TravelTab.entries.map { it.label })
    }

    @Test
    fun `each tab loads exactly one thing`() {
        assertTrue(TravelTab.CALENDAR.loadsObservances())
        assertFalse(TravelTab.CALENDAR.loadsTravelNews())
        assertFalse(TravelTab.CALENDAR.loadsAlerts())

        assertTrue(TravelTab.NEWS.loadsTravelNews())
        assertFalse(TravelTab.NEWS.loadsObservances())
        assertFalse(TravelTab.NEWS.loadsAlerts())

        assertTrue(TravelTab.ALERTS.loadsAlerts())
        assertFalse(TravelTab.ALERTS.loadsObservances())
        assertFalse(TravelTab.ALERTS.loadsTravelNews())
    }

    @Test
    fun `a tab index outside the strip reads as Calendar rather than crashing`() {
        assertTrue(travelTabAt(-1) == TravelTab.CALENDAR)
        assertTrue(travelTabAt(99) == TravelTab.CALENDAR)
        assertTrue(travelTabAt(1) == TravelTab.NEWS)
        assertTrue(travelTabAt(2) == TravelTab.ALERTS)
    }
}
