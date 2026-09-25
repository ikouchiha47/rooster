package com.personalos.app.ui.sources

import com.personalos.app.core.sources.CalendarSpec
import com.personalos.app.core.sources.SourceSpecs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adding a calendar region. The pure decision worth pinning: a chosen region
 * becomes a spec the real parser accepts back. No region is hardcoded — `japan`,
 * `india/west-bengal` and Google's `en.german` are all just slugs the picker
 * handed over.
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
    fun `a google region keeps its dotted id and gets the google feed url`() {
        val spec = SourceSpecs.parse("calendar", calendarRegionSpec("google", "en.german")) as CalendarSpec
        assertEquals("google", spec.provider)
        assertEquals("en.german", spec.region)
        assertTrue(spec.url.startsWith("https://calendar.google.com/calendar/ical/en.german"))
    }

    @Test
    fun `an unknown provider is refused rather than guessed`() {
        val error =
            runCatching { calendarRegionSpec("no-such-provider", "japan") }
                .exceptionOrNull()
        assertNotNull("an unknown provider must fail loudly", error)
        assertTrue(error is IllegalArgumentException)
    }
}
