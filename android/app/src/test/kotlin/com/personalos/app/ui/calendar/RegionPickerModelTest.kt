package com.personalos.app.ui.calendar

import com.personalos.app.core.calendar.Region
import com.personalos.app.data.SourceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picker's decisions, tested without rendering: what typing filters, what a
 * country offers, and what is already followed.
 *
 * The point of the picker is that nobody has to know a slug, so the search must
 * match what a person would type ("bengal", "India") and never require the
 * stored form ("india/west-bengal").
 */
class RegionPickerModelTest {
    private val countries =
        listOf(
            Region("india", "India", subdivision = false),
            Region("japan", "Japan", subdivision = false),
            Region("indonesia", "Indonesia", subdivision = false),
        )

    private val subdivisions =
        listOf(
            Region("india/kerala", "Kerala", subdivision = true),
            Region("india/west-bengal", "West Bengal", subdivision = true),
        )

    @Test
    fun `every country is offered when nothing is typed`() {
        assertEquals(3, filterRegions(countries, "").size)
    }

    @Test
    fun `typing filters by the name a person reads`() {
        assertEquals(listOf("india", "indonesia"), filterRegions(countries, "ind").map { it.slug })
        assertEquals(listOf("japan"), filterRegions(countries, "JAP").map { it.slug })
    }

    @Test
    fun `typing also matches the slug, so either spelling finds it`() {
        assertEquals(listOf("india"), filterRegions(countries, "india").map { it.slug })
    }

    @Test
    fun `a query matching nothing yields nothing rather than everything`() {
        assertTrue(filterRegions(countries, "atlantis").isEmpty())
    }

    @Test
    fun `choosing a country offers the whole country first, then its subdivisions`() {
        val choices = countryChoices(countries.first(), subdivisions)
        assertEquals(listOf("india", "india/kerala", "india/west-bengal"), choices.map { it.slug })
    }

    @Test
    fun `a country with no subdivisions still offers the whole country`() {
        val choices = countryChoices(Region("japan", "Japan", subdivision = false), emptyList())
        assertEquals(listOf("japan"), choices.map { it.slug })
    }

    @Test
    fun `the whole-country choice is labelled so it cannot be confused with a subdivision`() {
        assertEquals("All of India", wholeCountryLabel(Region("india", "India", subdivision = false)))
    }

    @Test
    fun `what is already followed is known, so it is never offered twice`() {
        val followed = setOf(followedKey("officeholidays", "india/west-bengal"), followedKey("google", "en.indian"))
        assertTrue(isFollowed("officeholidays", "india/west-bengal", followed))
        assertTrue(!isFollowed("officeholidays", "india/kerala", followed))
    }

    @Test
    fun `a region followed from one provider is still offered by the other`() {
        val followed = setOf(followedKey("google", "india"))
        assertTrue(isFollowed("google", "india", followed))
        assertTrue(
            "the same slug from officeholidays is a different source",
            !isFollowed("officeholidays", "india", followed),
        )
    }

    @Test
    fun `already-followed regions are dropped from what a query offers`() {
        val followed = setOf(followedKey("officeholidays", "japan"))
        val offered = pickableRegions(countries, "", "officeholidays", followed)
        assertEquals(listOf("india", "indonesia"), offered.map { it.slug })
    }

    @Test
    fun `the picker's list is capped in the model, so no unbounded list is rendered`() {
        val many = (1..40).map { Region("country-$it", "Country $it", subdivision = false) }
        assertEquals(PICKER_MAX_ROWS, pickableRegions(many, "", "officeholidays", emptySet()).size)
        assertEquals(0, pickableRegions(many, "", "officeholidays", emptySet(), limit = 0).size)
    }

    @Test
    fun `a provider with no subdivisions offers only the whole country`() {
        val google = Region("en.german", "Germany", subdivision = false)
        assertEquals(listOf("en.german"), pickableRegions(countryChoices(google, emptyList()), "", "google", emptySet()).map { it.slug })
    }

    private fun calendarSource(
        id: String,
        provider: String,
        region: String,
    ): SourceEntity =
        SourceEntity(
            id = id,
            name = region,
            kind = "calendar",
            specJson = """{"provider":"$provider","region":"$region","url":"https://example.com/ics/$region"}""",
            seeded = false,
            enabled = true,
            createdAt = 0L,
            updatedAt = 0L,
        )

    @Test
    fun `a followed region is labelled by its display name, never its slug`() {
        val labels =
            calendarRegionLabels(
                listOf(
                    calendarSource("cal-1", "google", "en.german"),
                    calendarSource("cal-2", "officeholidays", "india/west-bengal"),
                ),
            )
        assertEquals(setOf("Germany", "India / West Bengal"), labels.values.toSet())
    }

    @Test
    fun `a non-calendar source contributes no region label`() {
        val labels = calendarRegionLabels(listOf(calendarSource("cal-1", "google", "en.german").copy(kind = "rss")))
        assertTrue(labels.isEmpty())
    }
}
