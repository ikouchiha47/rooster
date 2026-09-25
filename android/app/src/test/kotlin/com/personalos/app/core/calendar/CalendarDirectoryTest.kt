package com.personalos.app.core.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The provider's region directory, parsed from its own pages.
 *
 * This exists because asking a person to type `india/west-bengal` is a memory
 * test, not a UI. The provider already publishes the choices; these parsers are
 * how they reach a picker.
 *
 * The HTML shapes below are copied from the live pages, including the `<i>`
 * sprite tag that sits between the href and the country name.
 */
class CalendarDirectoryTest {
    private val countriesHtml =
        """
        <h2>All Countries</h2>
        <ul>
          <li><a href="https://www.officeholidays.com/countries/afghanistan"><i class="sprite sprite-AF"></i>&nbsp;&nbsp;Afghanistan</a></li>
          <li><a href="https://www.officeholidays.com/countries/india"><i class="sprite sprite-IN"></i>&nbsp;&nbsp;India</a></li>
          <li><a href="https://www.officeholidays.com/countries/japan"><i class="sprite sprite-JP"></i>&nbsp;&nbsp;Japan</a></li>
          <li><a href="https://www.officeholidays.com/countries/india"><i class="sprite sprite-IN"></i>&nbsp;&nbsp;India</a></li>
          <li><a href="https://www.officeholidays.com/countries/by-continent">by Region</a></li>
        </ul>
        """.trimIndent()

    private val indiaHtml =
        """
        <table>
          <tr><td><a class="country-listing" title="Manipur, West Bengal" href="https://www.officeholidays.com/holidays/india/gann-ngai">Gaan Ngai</a></td></tr>
          <tr><td><a class="country-listing" title="West Bengal" href="https://www.officeholidays.com/holidays/india/west-bengal/poila-boishakh">Poila Boishakh</a></td></tr>
          <tr><td><a class="country-listing" title="Kerala" href="https://www.officeholidays.com/holidays/india/kerala/sree-narayana-guru-samadhi">Sree Narayana Guru Samadhi</a></td></tr>
          <tr><td><a class="country-listing" title="West Bengal" href="https://www.officeholidays.com/holidays/india/west-bengal/durga-puja">Durga Puja</a></td></tr>
          <tr><td><a class="country-listing" title="National" href="https://www.officeholidays.com/holidays/india/republic-day">Republic Day</a></td></tr>
        </table>
        """.trimIndent()

    // ------------------------------------------------------------------ countries

    @Test
    fun `countries are parsed with a readable name, not just a slug`() {
        val countries = CalendarDirectoryParser.parseCountries(countriesHtml)
        assertEquals(listOf("afghanistan", "india", "japan"), countries.map { it.slug })
        assertEquals(listOf("Afghanistan", "India", "Japan"), countries.map { it.name })
        assertTrue("a country is not a subdivision", countries.none { it.subdivision })
    }

    @Test
    fun `a repeated country collapses to one choice`() {
        assertTrue(CalendarDirectoryParser.parseCountries(countriesHtml).size == 3)
    }

    @Test
    fun `a non-country link is not offered as a country`() {
        assertTrue(CalendarDirectoryParser.parseCountries(countriesHtml).none { it.slug == "by-continent" })
    }

    @Test
    fun `a page that is not a country list yields nothing rather than nonsense`() {
        assertEquals(emptyList<Region>(), CalendarDirectoryParser.parseCountries(""))
        assertEquals(emptyList<Region>(), CalendarDirectoryParser.parseCountries("<html><body>nope</body></html>"))
    }

    // -------------------------------------------------------------- subdivisions

    @Test
    fun `subdivisions come from the country page's own region links, deduped`() {
        val subs = CalendarDirectoryParser.parseSubdivisions(indiaHtml, "india")
        assertEquals(
            "the slug stays the full path, because that is what a source stores",
            listOf("india/kerala", "india/west-bengal"),
            subs.map { it.slug }.sorted(),
        )
        assertTrue("every one of them is a subdivision", subs.all { it.subdivision })
        assertEquals("the label is the subdivision alone", listOf("Kerala", "West Bengal"), subs.map { it.name }.sorted())
    }

    @Test
    fun `a holiday that belongs to no single subdivision is not a subdivision`() {
        // `…/holidays/india/gann-ngai` is country-level: there is no region segment.
        val subs = CalendarDirectoryParser.parseSubdivisions(indiaHtml, "india")
        assertTrue("'gann-ngai' must not appear as a region", subs.none { it.slug == "gann-ngai" })
    }

    @Test
    fun `the display name comes from the slug, never from the states-it-applies-to title`() {
        val subs = CalendarDirectoryParser.parseSubdivisions(indiaHtml, "india").associateBy { it.slug }
        // The Gaan Ngai row's title names four states; the region name must not become that.
        assertEquals("West Bengal", subs.getValue("india/west-bengal").name)
        assertEquals("Kerala", subs.getValue("india/kerala").name)
    }

    @Test
    fun `subdivisions for another country are not attributed to this one`() {
        assertTrue(
            CalendarDirectoryParser
                .parseSubdivisions(indiaHtml, "japan")
                .isEmpty(),
        )
    }

    @Test
    fun `a garbage country page yields nothing rather than a crash`() {
        assertEquals(emptyList<Region>(), CalendarDirectoryParser.parseSubdivisions("", "india"))
    }

    // ------------------------------------------------------------- display name

    @Test
    fun `a region slug reads as a place name`() {
        assertEquals("West Bengal", CalendarDirectoryParser.displayName("west-bengal"))
        assertEquals("Japan", CalendarDirectoryParser.displayName("japan"))
        assertEquals("Andhra Pradesh", CalendarDirectoryParser.displayName("andhra-pradesh"))
        assertEquals("India / West Bengal", CalendarDirectoryParser.displayName("india/west-bengal"))
    }
}
