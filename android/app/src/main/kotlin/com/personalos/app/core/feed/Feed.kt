package com.personalos.app.core.feed

import com.personalos.app.core.tag.Tags

/** Event categories produced by feed sources (SMS classes are separate). */
object FeedCategories {
    const val NEWS = "NEWS"
    const val INCIDENT = "INCIDENT"
}

/**
 * A subscribable source: an RSS/Atom feed.
 *
 * A source **declares its tags** - it is the authority on what it publishes, and
 * every item it yields inherits all of them. This is multi-valued on purpose: a
 * travel desk feed is `news` + `travel`, a markets feed is `news` + `finance`,
 * a status page is `incident` and nothing else.
 *
 * There is deliberately **no default tag**. A new source must say what it is,
 * because defaulting to `news` is how a status page's outage rows ended up in
 * general news.
 */
data class FeedSource(
    val id: String,
    val name: String,
    val url: String,
    val language: String = "en",
    /** The tag its items always carry, and the Radar tab they land in. */
    val primaryTag: String,
    /** Further tags every item from this source also carries. */
    val extraTags: Set<String> = emptySet(),
) {
    /** Everything an item from this source is tagged with. */
    val tags: Set<String> get() = extraTags + primaryTag

    /**
     * Denormalised single-valued view of [primaryTag], kept because Radar's tabs
     * predate tags and filter on the `events.category` column. Derived, never
     * set independently, so it cannot drift from the tags.
     */
    val category: String get() = primaryTag.uppercase()
}

/** One parsed entry from a feed. */
data class FeedItem(
    val title: String,
    val link: String?,
    val summary: String?,
    val publishedAt: Long?,
)

/** Convenience for the common case: a plain news feed. */
fun newsSource(
    id: String,
    name: String,
    url: String,
    language: String = "en",
    extraTags: Set<String> = emptySet(),
): FeedSource =
    FeedSource(
        id = id,
        name = name,
        url = url,
        language = language,
        primaryTag = Tags.NEWS,
        extraTags = extraTags,
    )
