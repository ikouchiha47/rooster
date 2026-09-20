package com.personalos.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One dated observance: a public holiday, a festival, or a regional closure
 * (Calendar tracks holidays, festivals, and the seasons people travel around).
 *
 * `uid` is the feed's own identity for the row, so re-syncing a feed upserts
 * rather than duplicating — and a corrected date is a replacement, not a second
 * row. `fetched_at` is what lets a sync drop rows the feed no longer lists
 * without touching another feed's rows.
 *
 * `starts_at` is the same date as `date`, as epoch ms at UTC midnight, so the
 * "next N days" read is one indexed range query instead of string arithmetic.
 * A multi-day observance is one row per date until the feed says otherwise.
 */
@Entity(
    tableName = "calendar_dates",
    indices = [
        Index(value = ["region", "starts_at"]),
        Index(value = ["starts_at"]),
    ],
)
data class CalendarDateEntity(
    @PrimaryKey @ColumnInfo(name = "uid") val uid: String,
    /** Region slug (`india`, `india-west-bengal`); `india` covers everyone. */
    @ColumnInfo(name = "region") val region: String,
    /** `national` | `regional` — where it applies. */
    @ColumnInfo(name = "kind") val kind: String,
    /** ISO `YYYY-MM-DD`, for display and month grouping. */
    @ColumnInfo(name = "date") val date: String,
    /** [date] as epoch ms UTC midnight — the queryable form. */
    @ColumnInfo(name = "starts_at") val startsAt: Long,
    @ColumnInfo(name = "name") val name: String,
    /** The feed this row came from, e.g. `calendar:india-west-bengal`. */
    @ColumnInfo(name = "source") val source: String,
    /** Epoch ms of the sync that wrote this row. */
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
)
