package com.personalos.app.core.sources

import com.personalos.app.core.calendar.CalendarProviders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceKeysTest {
    @Test
    fun `search rows keep the stored gnews slug`() {
        val spec =
            SearchSpec(
                query = "West Bengal",
                queryLangCode = "en",
                sourceLocale = "en-IN",
                tags = setOf("news"),
            )
        assertEquals("gnews:west-bengal", SourceKeys.sourceFor("any-id", spec))
    }

    @Test
    fun `user rss rows are keyed by stable source id, not the editable name`() {
        val spec = RssSpec(url = "https://example.com/feed", tags = setOf("news"))
        val first = SourceKeys.sourceFor("ulid-1", spec)
        assertTrue(first.startsWith(SourceKeys.USER_RSS_PREFIX))
        assertEquals(first, SourceKeys.sourceFor("ulid-1", spec))
    }

    @Test
    fun `a calendar identity names its provider as well as its region`() {
        val spec =
            CalendarSpec(
                provider = "officeholidays",
                region = "india/west-bengal",
                url = "https://www.officeholidays.com/ics/india/west-bengal",
            )
        assertEquals("calendar:officeholidays:india/west-bengal", SourceKeys.sourceFor("cal-1", spec))
    }

    @Test
    fun `the same region from two providers is two identities, so neither sync prunes the other`() {
        val office =
            CalendarSpec(
                provider = "officeholidays",
                region = "japan",
                url = "https://www.officeholidays.com/ics/japan",
            )
        val google =
            CalendarSpec(
                provider = "google",
                region = "japan",
                url = "https://calendar.google.com/calendar/ical/japan",
            )

        assertEquals("calendar:officeholidays:japan", SourceKeys.sourceFor("a", office))
        assertEquals("calendar:google:japan", SourceKeys.sourceFor("b", google))
        assertNotEquals(SourceKeys.sourceFor("a", office), SourceKeys.sourceFor("b", google))
    }

    @Test
    fun `a calendar identity normalizes its region like the adapter does`() {
        val spec =
            CalendarSpec(
                provider = "officeholidays",
                region = "  India / West-Bengal/ ",
                url = "https://www.officeholidays.com/ics/india/west-bengal",
            )
        assertEquals(
            "calendar:officeholidays:${CalendarProviders.normalize("India / West-Bengal")}",
            SourceKeys.sourceFor("cal-1", spec),
        )
    }
}
