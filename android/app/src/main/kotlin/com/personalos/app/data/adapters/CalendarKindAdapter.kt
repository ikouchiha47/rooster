package com.personalos.app.data.adapters

import android.util.Log
import com.personalos.app.core.calendar.CalendarDates
import com.personalos.app.core.calendar.CalendarProviders
import com.personalos.app.core.calendar.IcsCalendar
import com.personalos.app.core.net.Http
import com.personalos.app.core.sources.CalendarSpec
import com.personalos.app.core.sources.SourceKeys
import com.personalos.app.data.CalendarDao
import com.personalos.app.data.CalendarDateEntity
import com.personalos.app.data.SourceEntity

/**
 * `kind = "calendar"`: a region's holiday feed into `calendar_dates`.
 *
 * Writes observances, never events — a public holiday is a dated fact, not a
 * news item. The feed's own UID is the row identity, so re-syncing corrects a
 * date in place instead of duplicating it, and rows the feed has dropped are
 * pruned by `fetched_at` so one region's sync can never clear another's.
 *
 * Region and URL both come from the source's spec: the user chose the region,
 * the provider resolved the URL. Nothing here knows a country name.
 */
class CalendarKindAdapter(
    private val dao: CalendarDao,
    private val cache: CachedBody,
    private val fetch: (String) -> String = { url -> Http.getText(url, accept = "text/calendar") },
) : KindAdapter {
    override val kindId: String = "calendar"

    override suspend fun ingest(
        source: SourceEntity,
        now: Long,
    ): Int {
        val spec =
            runCatching { parseSpec(source.specJson) as CalendarSpec }
                .onFailure { Log.w(TAG, "bad calendar spec for ${source.id}", it) }
                .getOrNull() ?: return 0

        val region = CalendarProviders.normalize(spec.region)
        val raw =
            cache.get(
                key = "calendar:$region",
                ttlMs = REFRESH_MS,
                now = now,
            ) {
                runCatching { fetch(spec.url) }
                    .onFailure { Log.w(TAG, "calendar fetch failed: $region", it) }
                    .getOrNull()
            } ?: return 0

        val identity = SourceKeys.sourceFor(source.id, spec)
        val entries = IcsCalendar.parse(raw)
        val rows =
            entries.mapNotNull { entry ->
                val startsAt = CalendarDates.toEpochMsUtc(entry.date) ?: return@mapNotNull null
                CalendarDateEntity(
                    feedUid = entry.uid,
                    region = region,
                    kind = entry.kind.serialName,
                    date = entry.date,
                    startsAt = startsAt,
                    name = entry.name,
                    source = identity,
                    fetchedAt = now,
                )
            }
        if (rows.isEmpty()) {
            Log.w(TAG, "calendar feed for $region parsed 0 rows; keeping stored rows")
            return 0
        }
        val entry =
            runCatching { dao.upsertAll(rows) }
                .onFailure { Log.w(TAG, "calendar upsert failed for $region", it) }
                .getOrNull() ?: return 0
        // Only after a successful write: a failed fetch must never prune.
        runCatching { dao.deleteStaleForSource(identity, now) }
            .onFailure { Log.w(TAG, "calendar prune failed for $region", it) }
        Log.i(TAG, "calendar $region: ${rows.size} observances")
        return entry.size
    }

    private companion object {
        const val TAG = "CalendarAdapter"

        /**
         * A holiday feed carries whole years and changes a handful of times a
         * year, so a monthly read is generous. Refetching it hourly — which is
         * what happens with no window at all — is pure waste.
         */
        const val REFRESH_MS = 30L * 86_400_000L
    }
}
