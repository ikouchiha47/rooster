package com.personalos.app.ui.calendar

import com.personalos.app.core.calendar.CalendarDirectoryParser
import com.personalos.app.core.calendar.CalendarProviders
import com.personalos.app.core.calendar.Region
import com.personalos.app.core.sources.CalendarSpec
import com.personalos.app.core.sources.SourceKeys
import com.personalos.app.core.sources.SourceSpecs
import com.personalos.app.data.SourceEntity

/*
 * The picker's decisions, kept out of the composable so they can be tested:
 * what typing filters, what choosing a country offers, what is already
 * followed, and how much is drawn at once.
 *
 * The whole point is that nobody has to know a slug, so matching is on the
 * **readable name** as well as the stored slug — "bengal" and "West Bengal"
 * both find `india/west-bengal`.
 */

/**
 * How many rows the picker draws at once.
 *
 * The picker sits inside a scrolling parent, so it must not host its own lazy
 * list; it draws a bounded slice instead. The cap is a display decision, so it
 * lives here and is applied by [pickableRegions], not by a composable.
 */
const val PICKER_MAX_ROWS = 12

fun filterRegions(
    regions: List<Region>,
    query: String,
): List<Region> {
    val q = query.trim()
    if (q.isEmpty()) return regions
    return regions.filter { it.name.contains(q, ignoreCase = true) || it.slug.contains(q, ignoreCase = true) }
}

/**
 * A region's followed identity is provider-qualified, because the same region
 * followed from Google and from OfficeHolidays is two different sources.
 */
fun followedKey(
    providerId: String,
    slug: String,
): String = "${providerId.lowercase()}:${slug.lowercase()}"

/** True when this exact provider+region is already a source. */
fun isFollowed(
    providerId: String,
    slug: String,
    followed: Set<String>,
): Boolean = followedKey(providerId, slug) in followed

/**
 * What the picker offers: the query's matches, without what is already
 * followed, capped at [limit]. All three decisions in one testable place.
 */
fun pickableRegions(
    regions: List<Region>,
    query: String,
    providerId: String,
    followed: Set<String>,
    limit: Int = PICKER_MAX_ROWS,
): List<Region> =
    filterRegions(regions, query)
        .filterNot { isFollowed(providerId, it.slug, followed) }
        .take(limit)

/**
 * What a chosen country offers: the whole country, then its subdivisions.
 * The whole-country option comes first because it is the common case.
 */
fun countryChoices(
    country: Region,
    subdivisions: List<Region>,
): List<Region> = listOf(country) + subdivisions

/** `All of India` — so the whole-country option is never mistaken for a state. */
fun wholeCountryLabel(country: Region): String = "All of ${country.name}"

/** The provider-qualified key a calendar row is already followed under, or null. */
fun calendarFollowKey(row: SourceEntity): String? {
    if (row.kind != "calendar") return null
    val spec = runCatching { SourceSpecs.parse("calendar", row.specJson) as CalendarSpec }.getOrNull() ?: return null
    return followedKey(spec.provider, CalendarProviders.normalize(spec.region))
}

/** A followed calendar row's display label: the provider's name for its region, never the slug. */
fun calendarRegionLabel(row: SourceEntity): String {
    val spec = runCatching { SourceSpecs.parse("calendar", row.specJson) as CalendarSpec }.getOrNull() ?: return row.name
    return CalendarProviders.byId(spec.provider)?.displayName(spec.region) ?: CalendarDirectoryParser.displayName(spec.region)
}

/**
 * Display label for every followed calendar region, keyed by the calendar row's
 * `source` identity. Pure: it reads specs and asks each provider for its
 * region's name, so a screen never prints a slug.
 */
fun calendarRegionLabels(sources: List<SourceEntity>): Map<String, String> =
    sources
        .asSequence()
        .filter { it.kind == "calendar" }
        .mapNotNull { row ->
            val spec = runCatching { SourceSpecs.parse("calendar", row.specJson) as CalendarSpec }.getOrNull() ?: return@mapNotNull null
            SourceKeys.sourceFor(row.id, spec) to calendarRegionLabel(row)
        }.toMap()
