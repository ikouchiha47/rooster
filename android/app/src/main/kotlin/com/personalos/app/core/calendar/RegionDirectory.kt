package com.personalos.app.core.calendar

/**
 * Reads one directory page. `null` means it could not be read — not "no rows",
 * because those are different facts and only one of them is an error.
 */
fun interface DirectoryFetch {
    suspend fun text(url: String): String?
}

/**
 * The regions a provider offers, as a choice rather than a vocabulary to guess.
 *
 * Two shapes ship, and both satisfy this one contract (Liskov):
 *  - [BundledRegionDirectory]: the app carries the list (Google has no page to
 *    read and no subdivisions).
 *  - [PublishedRegionDirectory]: the provider's own pages are read
 *    (OfficeHolidays, the only one with states and provinces).
 *
 * A third provider is a third implementation and is bound where providers are
 * built; nothing in the picker names a provider or a shape.
 */
interface RegionDirectory {
    /** Whether [subdivisions] can return anything for this provider. */
    val hasSubdivisions: Boolean

    /**
     * The countries offered, or a failure when the directory could not be read.
     * A failure is never turned into an empty success: "we could not ask" and
     * "there is nothing" must stay distinct.
     */
    suspend fun countries(fetch: DirectoryFetch): Result<List<Region>>

    /**
     * [country]'s subdivisions; empty is a legitimate answer (a provider with
     * none, or a country with none), while a failure is not.
     */
    suspend fun subdivisions(
        country: Region,
        fetch: DirectoryFetch,
    ): Result<List<Region>>

    /** A readable name for a stored region slug. Never returns the slug itself. */
    fun displayName(region: String): String
}

/**
 * A fixed list the app ships. There is nothing to fetch, so there is nothing
 * that can fail, and [countries] ignores its [DirectoryFetch].
 */
class BundledRegionDirectory(
    private val regions: List<Region>,
) : RegionDirectory {
    override val hasSubdivisions: Boolean = false

    override suspend fun countries(fetch: DirectoryFetch): Result<List<Region>> = Result.success(regions)

    override suspend fun subdivisions(
        country: Region,
        fetch: DirectoryFetch,
    ): Result<List<Region>> = Result.success(emptyList())

    override fun displayName(region: String): String =
        regions.firstOrNull { it.slug.equals(region, ignoreCase = true) }?.name
            ?: CalendarDirectoryParser.displayName(region)
}

/**
 * The provider's own pages: [countriesUrl] lists countries, [countryUrl] is one
 * country's page whose region links give its subdivisions.
 *
 * A blank page or a failed fetch is a [Result.failure], because the picker must
 * be able to say "the directory could not be loaded" rather than show nothing.
 */
class PublishedRegionDirectory(
    private val countriesUrl: String,
    private val countryUrl: (String) -> String,
) : RegionDirectory {
    override val hasSubdivisions: Boolean = true

    override suspend fun countries(fetch: DirectoryFetch): Result<List<Region>> =
        runCatching {
            val html = fetch.text(countriesUrl) ?: throw IllegalStateException("could not read $countriesUrl")
            CalendarDirectoryParser
                .parseCountries(html)
                .ifEmpty { throw IllegalStateException("no countries found at $countriesUrl") }
        }

    override suspend fun subdivisions(
        country: Region,
        fetch: DirectoryFetch,
    ): Result<List<Region>> =
        runCatching {
            val html = fetch.text(countryUrl(country.slug)) ?: throw IllegalStateException("could not read ${countryUrl(country.slug)}")
            CalendarDirectoryParser.parseSubdivisions(html, country.slug)
        }

    override fun displayName(region: String): String = CalendarDirectoryParser.displayName(region)
}
