package com.personalos.app.core.sources

/**
 * Every source owns its `events.source` string, so ingest, retag recovery and
 * labels all resolve the same value from the same place instead of each
 * deriving its own.
 *
 * - `search` rows: `gnews:<query-slug>` via [GnewsUrl] — the strings already
 *   stored for every Google News item, so this must never change shape.
 * - user `rss` rows: `userrss:<sourceId>` — the source id is the only stable
 *   identity (names are editable). Seeded `rss` rows never receive items
 *   (the catalog drives those URLs), so they map harmlessly.
 */
object SourceKeys {
    const val USER_RSS_PREFIX = "userrss:"

    fun sourceFor(
        sourceId: String,
        spec: SourceSpec,
    ): String =
        when (spec) {
            is SearchSpec -> GnewsUrl.sourceFor(spec.query)
            is RssSpec -> "$USER_RSS_PREFIX$sourceId"
        }
}
