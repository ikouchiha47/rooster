package com.personalos.app.data

import androidx.room.Dao
import androidx.room.Query

/**
 * The re-tag walk's two reads, **per source family**: the family's newest id, and a
 * page below a cursor, newest first.
 *
 * Its own interface, like [RuleBackfillDao] and [ConversationDao]: walking the
 * store to re-tag is a separate reason to change, and [EventDao]'s six test fakes
 * stay untouched.
 *
 * Newest-first and per-family are both deliberate: a new tagger should reach
 * today's items before last year's, and a small family (SMS) should not wait
 * behind a large one (feeds) for the same walk. `prefix` is a `LIKE` prefix
 * (`"sms"`, `"rss:"`), or null for every source.
 */
@Dao
interface RetagDao {
    /** The top of one source family; a fresh walk of it starts here. */
    @Query(Sql.EVENTS_MAX_ID_FOR_PREFIX)
    suspend fun maxId(prefix: String?): Long?

    /** Up to [limit] rows of one family strictly below [beforeId], newest first. */
    @Query(Sql.EVENTS_BEFORE_ID)
    suspend fun itemsBefore(
        prefix: String?,
        beforeId: Long,
        limit: Int,
    ): List<RetagCandidate>
}
