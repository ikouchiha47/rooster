package com.personalos.app.core.calendar

/**
 * Holiday-feed providers, as data (ADR 0005 shape: a new provider is a new
 * entry, never a branch in the adapter).
 *
 * A provider owns how a **region** becomes a feed URL, so the app never
 * hardcodes a country: `japan`, `india/west-bengal` and `germany/bavaria` are
 * all just region slugs the user chose. Adding a provider whose URL shape
 * differs is one more entry here.
 */
data class CalendarProvider(
    val id: String,
    val name: String,
    /** Region slug -> feed. Region is a path (`country` or `country/subdivision`). */
    val urlFor: (String) -> String,
)

object CalendarProviders {
    /**
     * OfficeHolidays: one ICS per country and per subdivision. Verified
     * 2026-09-21 — `/ics/india` 414 events, `/ics/india/west-bengal` 53,
     * `/ics/india/karnataka` 51, all with the app's own User-Agent.
     */
    val officeHolidays =
        CalendarProvider(
            id = "officeholidays",
            name = "OfficeHolidays",
            urlFor = { region -> "${BASE}${normalize(region)}" },
        )

    val ALL: List<CalendarProvider> = listOf(officeHolidays)

    fun byId(id: String): CalendarProvider? = ALL.firstOrNull { it.id == id }

    /**
     * A region is a lower-case slug path: `country` or `country/subdivision`.
     * Trailing and duplicate slashes are removed so `India/West-Bengal/` and
     * `india/west-bengal` are one region, not two rows.
     */
    fun normalize(region: String): String =
        region
            .trim()
            .trim('/')
            .lowercase()
            .split('/')
            .map { it.trim().replace(Regex("[^a-z0-9-]+"), "-").trim('-') }
            .filter { it.isNotEmpty() }
            .joinToString("/")

    private const val BASE = "https://www.officeholidays.com/ics/"
}
