package com.personalos.app.core.sources

import com.personalos.app.core.calendar.CalendarProviders

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
            /*
             * The provider is part of the identity, not decoration: the same
             * region can be followed from Google **and** OfficeHolidays (Google's
             * `en.indian` and OfficeHolidays' `india` are different feeds with
             * different rows). Without the provider the two would share one
             * identity, and each sync's `deleteStaleForSource` would prune the
             * other's rows.
             *
             * Migration consequence, stated honestly: existing installs stored
             * `calendar:<region>`. Re-syncing writes rows under
             * `calendar:<provider>:<region>`, so the old rows are orphaned — they
             * share the region and so stay visible, but they are never fetched
             * again, which means this code alone does not prune them. Removing
             * them needs a one-off cleanup; it is not free.
             */
            is CalendarSpec -> "calendar:${slug(spec.provider)}:${CalendarProviders.normalize(spec.region)}"
            else -> throw IllegalArgumentException("no identity for spec ${spec::class.simpleName}")
        }
}
