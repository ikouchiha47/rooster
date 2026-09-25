package com.personalos.app.core.calendar

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two holiday providers, as data.
 *
 * Google is bundled and national-only; OfficeHolidays publishes its directory
 * and carries subdivisions. Adding a third provider must not require touching
 * this test — only these two shapes are pinned here.
 */
class CalendarProvidersTest {
    @Test
    fun `google is registered and supplies its own directory`() {
        val google = CalendarProviders.byId("google")
        assertEquals("Google", google?.name)
        assertTrue("google has no published directory page", google!!.directory is BundledRegionDirectory)
    }

    @Test
    fun `the google bundle carries a readable country name per id`() =
        runBlocking {
            val google = CalendarProviders.google
            val countries = google.directory.countries(DirectoryFetch { null }).getOrThrow()

            assertEquals("the verified ids, exactly", 25, countries.size)
            assertEquals("Germany", countries.single { it.slug == "en.german" }.name)
            assertEquals("United Kingdom", countries.single { it.slug == "en.uk" }.name)
            assertEquals("United States", countries.single { it.slug == "en.usa" }.name)
            assertTrue("google offers no states or provinces", !google.directory.hasSubdivisions)
        }

    @Test
    fun `the google feed url carries the locale id and the encoded group`() {
        val url = CalendarProviders.google.urlFor("en.german")
        assertTrue(url.startsWith("https://calendar.google.com/calendar/ical/en.german"))
        assertTrue("the # and @ of the group must be percent-encoded", url.contains("%23holiday%40group.v.calendar.google.com"))
    }

    @Test
    fun `officeholidays still publishes subdivisions`() {
        assertTrue(CalendarProviders.officeHolidays.directory.hasSubdivisions)
        assertTrue(CalendarProviders.officeHolidays.urlFor("Japan").endsWith("/japan"))
    }

    @Test
    fun `an unknown provider resolves to nothing rather than a guess`() {
        assertNull(CalendarProviders.byId("no-such-provider"))
    }

    @Test
    fun `normalize keeps google's dotted ids and officeholidays' paths`() {
        assertEquals("en.german", CalendarProviders.normalize("EN.German"))
        assertEquals("en.indian", CalendarProviders.normalize(" en.indian "))
        assertEquals("india/west-bengal", CalendarProviders.normalize("  India / West-Bengal/ "))
        assertEquals("", CalendarProviders.normalize("  "))
    }

    @Test
    fun `a google region reads as its country, not its id`() {
        assertEquals("Germany", CalendarProviders.google.displayName("en.german"))
    }
}
