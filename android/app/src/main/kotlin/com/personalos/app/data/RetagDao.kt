package com.personalos.app.data

import androidx.room.Dao
import androidx.room.Query

/**
 * The re-tag backlog's two reads, **per source family**: up to a bounded batch,
 * and its depth.
 *
 * The backlog is derived from the store (ADR 0010 §3): no marker for the active
 * tagger, or an enrichment newer than the marker. That makes enrichment visible
 * to retag without a queue, an edge, or a second write from the enricher.
 *
 * `prefix` is a `LIKE` prefix (`"sms"`, `"rss:"`), or null for every source.
 */
@Dao
interface RetagDao {
    /** Up to [limit] items needing a re-tag, newest first, within [prefix]. */
    @Query(Sql.RETAG_BACKLOG)
    suspend fun itemsNeedingRetag(
        taggerId: String?,
        prefix: String?,
        limit: Int,
    ): List<RetagCandidate>

    /** The same predicate without a limit, for depth and adaptive batches. */
    @Query(Sql.RETAG_BACKLOG_COUNT)
    suspend fun countNeedingRetag(
        taggerId: String?,
        prefix: String?,
    ): Int
}
