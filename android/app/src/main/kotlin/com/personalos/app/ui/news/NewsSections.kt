package com.personalos.app.ui.news

import com.personalos.app.data.DayHeader
import com.personalos.app.data.TaggedEvent
import com.personalos.app.data.work.SyncScheduler
import com.personalos.app.ui.common.DAY_MS

/**
 * Pure section helpers behind the News day list.
 *
 * Headers (`dayStart` + count) load first and each opened day pages on its own
 * cursor; these functions only shape what is already loaded, so they are
 * unit-tested without composing.
 */
internal fun todayStartMs(now: Long = System.currentTimeMillis()): Long = now / DAY_MS * DAY_MS

/**
 * The bucket read: ingest time when known, publish time for pre-v9 rows — the
 * Kotlin side of `COALESCE(ingested_at, timestamp)`. Ordering never uses this;
 * it only decides which sync-bucket separator a Today row sits under.
 */
fun effectiveIngestedAt(
    ingestedAt: Long?,
    publishedAt: Long,
): Long = ingestedAt ?: publishedAt

/** Start of the sync-bucket an effective ingest time falls in. */
fun syncBucketStart(
    effectiveAt: Long,
    groupMinutes: Long = SyncScheduler.SYNC_GROUP_MINUTES,
): Long {
    val groupMs = groupMinutes * 60_000L
    return effectiveAt / groupMs * groupMs
}

/** One sync-bucket of an already time-ordered day, newest first. */
data class SyncBucket<T>(
    val bucketStart: Long,
    val items: List<T>,
)

/**
 * Sequential sync-bucketing for a time-ordered day.
 *
 * Same shape as [com.personalos.app.ui.common.groupIntoDays]: a new bucket
 * starts wherever the sync-bucket changes, so input order is kept. Pure, so it
 * is unit-tested without composing.
 */
fun <T> groupIntoSyncBuckets(
    rows: List<T>,
    ingestedAtOf: (T) -> Long?,
    publishedAtOf: (T) -> Long,
    groupMinutes: Long = SyncScheduler.SYNC_GROUP_MINUTES,
): List<SyncBucket<T>> {
    val out = mutableListOf<SyncBucket<T>>()
    var current = Long.MIN_VALUE
    var bucket = mutableListOf<T>()
    for (row in rows) {
        val key = syncBucketStart(effectiveIngestedAt(ingestedAtOf(row), publishedAtOf(row)), groupMinutes)
        if (bucket.isNotEmpty() && key != current) {
            out.add(SyncBucket(current, bucket))
            bucket = mutableListOf()
        }
        current = key
        bucket.add(row)
    }
    if (bucket.isNotEmpty()) out.add(SyncBucket(current, bucket))
    return out
}

/**
 * How many ids sit above the last seen head: the drift count behind the N NEW
 * pill. A missing head means the day reloaded past what was seen, so
 * everything above is new; a null head means nothing was seen yet, so nothing
 * is new.
 */
fun freshCountAboveHead(
    ids: List<Long>,
    seenHeadId: Long?,
): Int {
    if (seenHeadId == null) return 0
    val at = ids.indexOf(seenHeadId)
    return if (at < 0) ids.size else at
}

/** One rendered day section: its store count plus the loaded (and filtered) rows. */
data class DaySection(
    val dayStart: Long,
    val total: Int,
    val rows: List<TaggedEvent>,
    /** Sync-buckets, for Today only; null on every other day. */
    val buckets: List<SyncBucket<TaggedEvent>>?,
)

/**
 * Shapes loaded pages into day sections: per-day search filtering, and sync
 * buckets for Today. Headers stay in store order (newest day first).
 */
fun buildDaySections(
    headers: List<DayHeader>,
    itemsByDay: Map<Long, List<TaggedEvent>>,
    today: Long,
    query: String,
    groupMinutes: Long = SyncScheduler.SYNC_GROUP_MINUTES,
): List<DaySection> {
    val q = query.trim()
    return headers.map { header ->
        val loaded = itemsByDay[header.dayStart].orEmpty()
        val rows =
            if (q.isEmpty()) {
                loaded
            } else {
                loaded.filter {
                    it.event.title.contains(q, ignoreCase = true) ||
                        it.event.content.contains(q, ignoreCase = true)
                }
            }
        val buckets =
            if (header.dayStart == today && rows.isNotEmpty()) {
                groupIntoSyncBuckets(rows, { it.event.ingestedAt }, { it.event.timestamp }, groupMinutes)
            } else {
                null
            }
        DaySection(header.dayStart, if (q.isEmpty()) header.count else rows.size, rows, buckets)
    }
}
