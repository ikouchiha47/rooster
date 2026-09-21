package com.personalos.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * One dated observance: a public holiday, a festival, or a regional closure
 * (Calendar tracks holidays, festivals, and the seasons people travel around).
 *
 * Identity is **(source, uid)**. `uid` is the feed's own row id and is unique
 * only *within* a feed: a national holiday carries the same uid in every
 * regional feed that lists it, so keying on uid alone would let one region's
 * sync rewrite another's row. `source` is declared first because the primary
 * key's column order is the order these are declared in, and that order is the
 * prefix SQLite can seek on.
 *
 * `fetched_at` is what lets a sync drop rows its feed no longer lists, without
 * touching another feed's rows. `starts_at` is `date` as epoch ms at UTC
 * midnight, so "next N days" is one indexed range query.
 */
@Entity(
    tableName = "calendar_dates",
    primaryKeys = ["source", "feed_uid"],
    indices = [
        Index(value = ["region", "starts_at"]),
        Index(value = ["starts_at"]),
    ],
)
data class CalendarDateEntity(
    /** The feed this row came from, e.g. `calendar:india/west-bengal`. */
    @ColumnInfo(name = "source") val source: String,
    /**
     * The feed's own `UID:` value. Named `feed_uid`, never `uid`, so it can
     * never be mistaken for our [`EventEntity.ulid`] — this one is minted by a
     * remote feed and is unique only within that feed.
     */
    @ColumnInfo(name = "feed_uid") val feedUid: String,
    /** Region slug (`japan`, `india/west-bengal`) the user chose. */
    @ColumnInfo(name = "region") val region: String,
    /** `national` | `regional` — where it applies. */
    @ColumnInfo(name = "kind") val kind: String,
    /** ISO `YYYY-MM-DD`, for display and month grouping. */
    @ColumnInfo(name = "date") val date: String,
    /** [date] as epoch ms UTC midnight — the queryable form. */
    @ColumnInfo(name = "starts_at") val startsAt: Long,
    @ColumnInfo(name = "name") val name: String,
    /** Epoch ms of the sync that wrote this row. */
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
)
