package com.personalos.app.data

import androidx.room.Dao
import androidx.room.Query

/**
 * The two reads a rule backfill needs: the newest id (the window's top) and a
 * page of items below a cursor, newest first.
 *
 * Its own interface, like [MessageSearchDao], [ConversationDao] and
 * [RuleFireDao]: a backfill is a separate reason to change, and [EventDao]'s
 * test fakes stay untouched. The window is walked newest-first so the most
 * recent items are evaluated first and a kill leaves the least-unfinished work.
 */
@Dao
interface RuleBackfillDao {
    /** The current top of the store; the window is anchored here on a fresh run. */
    @Query("SELECT MAX(id) FROM events")
    suspend fun maxId(): Long?

    /** Up to [limit] items strictly below [beforeId], newest first. */
    @Query("SELECT * FROM events WHERE id < :beforeId ORDER BY id DESC LIMIT :limit")
    suspend fun before(
        beforeId: Long,
        limit: Int,
    ): List<EventEntity>
}
