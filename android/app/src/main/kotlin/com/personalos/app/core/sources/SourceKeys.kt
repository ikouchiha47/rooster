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

    /** Slug for observation identities (`weather:bengaluru`, `fx:usd-inr`). */
    fun slug(value: String): String =
        value
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifEmpty { "unknown" }

    fun sourceFor(
        sourceId: String,
        spec: SourceSpec,
    ): String =
        when (spec) {
            is SearchSpec -> GnewsUrl.sourceFor(spec.query)
            is RssSpec -> "$USER_RSS_PREFIX$sourceId"
            is SmsSpec -> "sms"
            is WeatherSpec -> "weather:${slug(spec.place)}"
            is FxSpec -> "fx:${slug(spec.pair)}"
            is DeviceSpec -> "device:${slug(spec.signal)}"
            // The region already is a slug path, so it is the identity as-is.
            is CalendarSpec -> "calendar:${com.personalos.app.core.calendar.CalendarProviders.normalize(spec.region)}"
            else -> throw IllegalArgumentException("no identity for spec ${spec::class.simpleName}")
        }
}
