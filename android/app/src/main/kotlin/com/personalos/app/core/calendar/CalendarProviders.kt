package com.personalos.app.core.calendar

/**
 * Holiday-feed providers, as data (ADR 0005 shape: a new provider is a new
 * entry, never a branch in the adapter).
 *
 * A provider owns three things and nothing else:
 *  - how a **region** becomes a feed URL ([urlFor]);
 *  - where its **regions** come from ([directory]) — a bundled list or its own
 *    published pages, because the two providers genuinely differ;
 *  - its id and name.
 *
 * The app never hardcodes a country: `japan`, `india/west-bengal` and Google's
 * `en.german` are all just region slugs the picker handed over.
 */
data class CalendarProvider(
    val id: String,
    val name: String,
    /** Region slug -> feed. Region is a path (`country` or `country/subdivision`). */
    val urlFor: (String) -> String,
    /**
     * The choices this provider offers. A provider that publishes nothing (and
     * has no subdivisions) ships [BundledRegionDirectory] instead.
     */
    val directory: RegionDirectory,
) {
    /** A readable name for a stored region, so screens never print a slug. */
    fun displayName(region: String): String = directory.displayName(region)
}

object CalendarProviders {
    /**
     * The verified Google locale ids with the country each one names. Data, not
     * code: the feed URL is derived from the id, and the picker shows the name.
     *
     * Declared before [google], because an `object`'s properties initialize in
     * declaration order.
     */
    private val GOOGLE_REGIONS: List<Region> =
        listOf(
            Region("en.australian", "Australia", subdivision = false),
            Region("en.brazilian", "Brazil", subdivision = false),
            Region("en.canadian", "Canada", subdivision = false),
            Region("en.danish", "Denmark", subdivision = false),
            Region("en.dutch", "Netherlands", subdivision = false),
            Region("en.finnish", "Finland", subdivision = false),
            Region("en.french", "France", subdivision = false),
            Region("en.german", "Germany", subdivision = false),
            Region("en.greek", "Greece", subdivision = false),
            Region("en.indian", "India", subdivision = false),
            Region("en.indonesian", "Indonesia", subdivision = false),
            Region("en.irish", "Ireland", subdivision = false),
            Region("en.italian", "Italy", subdivision = false),
            Region("en.japanese", "Japan", subdivision = false),
            Region("en.mexican", "Mexico", subdivision = false),
            Region("en.norwegian", "Norway", subdivision = false),
            Region("en.polish", "Poland", subdivision = false),
            Region("en.portuguese", "Portugal", subdivision = false),
            Region("en.russian", "Russia", subdivision = false),
            Region("en.singapore", "Singapore", subdivision = false),
            Region("en.swedish", "Sweden", subdivision = false),
            Region("en.turkish", "Turkey", subdivision = false),
            Region("en.uk", "United Kingdom", subdivision = false),
            Region("en.usa", "United States", subdivision = false),
            Region("en.vietnamese", "Vietnam", subdivision = false),
        )

    /**
     * OfficeHolidays: one ICS per country and per subdivision. Verified
     * 2026-09-21 — `/ics/india` 414 events, `/ics/india/west-bengal` 53,
     * `/ics/india/karnataka` 51, all with the app's own User-Agent.
     *
     * The only provider with subdivisions (states and provinces, including
     * India's), read from its own country pages.
     */
    val officeHolidays =
        CalendarProvider(
            id = "officeholidays",
            name = "OfficeHolidays",
            urlFor = { region -> "${OFFICE_BASE}${normalize(region)}" },
            directory =
                PublishedRegionDirectory(
                    countriesUrl = "${OFFICE_SITE}countries",
                    countryUrl = { country -> "${OFFICE_SITE}countries/${normalize(country)}" },
                ),
        )

    /**
     * Google: national holidays per country, plain ICS, no key, OAuth or quota.
     *
     * Its locale ids are irregular (`<language>.<adjective>`: `en.german`, not
     * `germany`), it publishes no parseable directory page, and it has **no
     * subdivisions** — so its list is bundled data ([GOOGLE_REGIONS]) and its
     * directory says so. All 25 ids were verified working, 2021–2031
     * (en.indian 565 events, en.usa 317, en.german 462, en.japanese 297, ...).
     */
    val google =
        CalendarProvider(
            id = "google",
            name = "Google",
            urlFor = { id -> "$GOOGLE_BASE${normalize(id)}$GOOGLE_SUFFIX" },
            directory = BundledRegionDirectory(GOOGLE_REGIONS),
        )

    /** Every provider, in picker order. A third is one more element here. */
    val ALL: List<CalendarProvider> = listOf(officeHolidays, google)

    fun byId(id: String): CalendarProvider? = ALL.firstOrNull { it.id == id }

    /**
     * A region is a lower-case slug path: `country` or `country/subdivision`.
     * Trailing and duplicate slashes are removed so `India/West-Bengal/` and
     * `india/west-bengal` are one region, not two rows.
     *
     * A dot is preserved because Google's ids carry one (`en.german`); a feed
     * that used dots for any other reason is not in play. The result is the
     * same value the adapter stores and the identity names, so those can never
     * drift.
     */
    fun normalize(region: String): String =
        region
            .trim()
            .trim('/')
            .lowercase()
            .split('/')
            .map { it.trim().replace(Regex("[^a-z0-9.-]+"), "-").trim('-') }
            .filter { it.isNotEmpty() }
            .joinToString("/")

    private const val OFFICE_BASE = "https://www.officeholidays.com/ics/"
    private const val OFFICE_SITE = "https://www.officeholidays.com/"
    private const val GOOGLE_BASE = "https://calendar.google.com/calendar/ical/"
    private const val GOOGLE_SUFFIX = "%23holiday%40group.v.calendar.google.com/public/basic.ics"
}
