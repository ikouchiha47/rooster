package com.personalos.app.ui.sources

import com.personalos.app.core.calendar.CalendarProviders
import com.personalos.app.core.sources.CalendarSpec
import com.personalos.app.core.sources.SourceSpecs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adding a calendar region. Two pure decisions worth pinning: what a region
 * becomes (a spec the real parser accepts), and what counts as an invalid one.
 * No region is hardcoded — `japan` and `india/west-bengal` are both just slugs.
 */
class CalendarRegionTest {
    @Test
    fun `a region builds a spec the real parser accepts back`() {
        val json = calendarRegionSpec("officeholidays", "Japan")
        val spec = SourceSpecs.parse("calendar", json) as CalendarSpec

        assertEquals("officeholidays", spec.provider)
        assertEquals("japan", spec.region)
        assertTrue("the url follows the region", spec.url.endsWith("/japan"))
    }

    @Test
    fun `a subdivision keeps its path`() {
        val spec = SourceSpecs.parse("calendar", calendarRegionSpec("officeholidays", "India / West-Bengal")) as CalendarSpec
        assertEquals("india/west-bengal", spec.region)
        assertTrue(spec.url.endsWith("/india/west-bengal"))
    }

    @Test
    fun `an unknown provider is refused rather than guessed`() {
        val error =
            runCatching { calendarRegionSpec("no-such-provider", "japan") }
                .exceptionOrNull()
        assertNotNull("an unknown provider must fail loudly", error)
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `a blank or unusable region is refused with a usable hint`() {
        val blank = validateCalendarRegion("   ", emptySet())
        assertNotNull(blank)
        assertTrue("the message should suggest a shape", blank!!.contains("country/subdivision"))

        assertNotNull("punctuation is not a region", validateCalendarRegion("!!!", emptySet()))
    }

    @Test
    fun `a region already subscribed is refused by name`() {
        val existing = setOf(CalendarProviders.normalize("India / West-Bengal"))
        val error = validateCalendarRegion("india/west-bengal", existing)
        assertNotNull(error)
        assertTrue("names what is already there", error!!.contains("india/west-bengal"))
    }

    @Test
    fun `a fresh region is accepted`() {
        assertNull(validateCalendarRegion("Karnataka", setOf("india/west-bengal")))
    }
}
