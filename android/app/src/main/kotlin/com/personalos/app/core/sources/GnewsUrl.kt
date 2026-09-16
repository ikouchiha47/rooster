package com.personalos.app.core.sources

import java.net.URLEncoder
import java.util.Locale

/**
 * Portable sources unit (ADR 0003): the Google News RSS query shape.
 *
 * Pure Kotlin, zero `android.*` imports — see [SourceKind]. `java.net` is JDK,
 * available to both the app and the unit tests.
 */
object GnewsUrl {
    /** Source string written into `events.source`, e.g. `gnews:west-bengal`. */
    const val SOURCE_PREFIX = "gnews:"

    /**
     * The v1 GNews edition: every seed queries `en-IN`. The query is
     * URL-encoded; everything else is the fixed template.
     */
    fun build(query: String): String = "https://news.google.com/rss/search?q=${URLEncoder.encode(query, "UTF-8")}&hl=en-IN&gl=IN&ceid=IN:en"

    /** URL-safe identity for a query: lowercase, whitespace runs to hyphens. */
    fun slug(query: String): String = query.trim().lowercase(Locale.ROOT).replace(WHITESPACE, "-")

    /** The `events.source` value for items ingested from [query]. */
    fun sourceFor(query: String): String = SOURCE_PREFIX + slug(query)

    private val WHITESPACE = Regex("\\s+")
}
