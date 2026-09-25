package com.personalos.app.core.calendar

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A provider's regions, as a directory the picker reads.
 *
 * There are two shapes and both must be substitutable behind [RegionDirectory]:
 * Google ships a **bundled** list and has no subdivisions; OfficeHolidays
 * **publishes** pages and does. A third provider is a third implementation, not
 * a new branch in the picker — these tests are what pins that.
 */
class RegionDirectoryTest {
    private val bundled =
        BundledRegionDirectory(
            listOf(
                Region("en.german", "Germany", subdivision = false),
                Region("en.japanese", "Japan", subdivision = false),
            ),
        )

    private val countriesHtml =
        """
        <li><a href="https://www.officeholidays.com/countries/india"><i class="sprite sprite-IN"></i>&nbsp;&nbsp;India</a></li>
        <li><a href="https://www.officeholidays.com/countries/japan"><i class="sprite sprite-JP"></i>&nbsp;&nbsp;Japan</a></li>
        """.trimIndent()

    private val indiaHtml =
        """
        <a href="https://www.officeholidays.com/holidays/india/west-bengal/durga-puja">Durga Puja</a>
        <a href="https://www.officeholidays.com/holidays/india/kerala/onam">Onam</a>
        """.trimIndent()

    private val published =
        PublishedRegionDirectory(
            countriesUrl = "https://www.officeholidays.com/countries",
            countryUrl = { country -> "https://www.officeholidays.com/countries/$country" },
        )

    // ------------------------------------------------------------------ bundled

    @Test
    fun `a bundled provider answers from its shipped data, without a fetch`() =
        runBlocking {
            var fetched = false
            val fetch =
                DirectoryFetch { _ ->
                    fetched = true
                    null
                }

            val countries = bundled.countries(fetch).getOrThrow()

            assertEquals(listOf("en.german", "en.japanese"), countries.map { it.slug })
            assertTrue("a bundled list never means a request", !fetched)
        }

    @Test
    fun `a bundled provider says it has no subdivisions`() =
        runBlocking {
            assertTrue(!bundled.hasSubdivisions)
            assertTrue(bundled.subdivisions(Region("en.german", "Germany", false), DirectoryFetch { null }).getOrThrow().isEmpty())
        }

    @Test
    fun `a bundled region reads as its country name, not its id`() {
        assertEquals("Germany", bundled.displayName("en.german"))
        assertEquals("Japan", bundled.displayName("en.japanese"))
    }

    @Test
    fun `an unknown region falls back to the slug's readable form`() {
        assertEquals("West Bengal", bundled.displayName("west-bengal"))
    }

    // ----------------------------------------------------------------- published

    @Test
    fun `a published provider parses its own countries page`() =
        runBlocking {
            val countries =
                published
                    .countries(DirectoryFetch { countriesHtml })
                    .getOrThrow()
            assertEquals(listOf("india", "japan"), countries.map { it.slug }.sorted())
            assertEquals(listOf("India", "Japan"), countries.map { it.name }.sorted())
        }

    @Test
    fun `a published provider has subdivisions`() =
        runBlocking {
            assertTrue(published.hasSubdivisions)
            val subs =
                published
                    .subdivisions(
                        Region("india", "India", subdivision = false),
                        DirectoryFetch { indiaHtml },
                    ).getOrThrow()
            assertEquals(listOf("india/kerala", "india/west-bengal"), subs.map { it.slug }.sorted())
        }

    @Test
    fun `a directory that cannot be fetched is a failure, never a blank success`() =
        runBlocking {
            val result = published.countries(DirectoryFetch { null })
            assertTrue("'we could not ask' must not look like 'there is nothing'", result.isFailure)
        }

    @Test
    fun `a page that parses to nothing is a failure, not an empty picker`() =
        runBlocking {
            val result = published.countries(DirectoryFetch { "<html>maintenance</html>" })
            assertTrue(result.isFailure)
        }

    @Test
    fun `a region path reads as a place name`() {
        assertEquals("India / West Bengal", published.displayName("india/west-bengal"))
    }
}
