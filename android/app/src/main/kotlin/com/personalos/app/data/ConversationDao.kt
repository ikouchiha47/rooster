package com.personalos.app.data

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Messages-scoped reads over the materialised `conversations` table (v20), plus
 * the one thread page a conversation opens.
 *
 * Its own interface, like [MessageSearchDao] and [BookmarkDao]: grouping is a
 * separate reason to change, and [EventDao]'s existing test fakes stay untouched.
 *
 * The table is maintained by triggers on `events`, not by these methods — that
 * is deliberate, so no writer can forget to keep it in step. Room cannot
 * validate the triggers, so `MigrationSchemaTest` and `ConversationTriggerTest`
 * are what prove them.
 */
@Dao
interface ConversationDao {
    /** Recent: bounded, recency-ordered, never paged. */
    @Query(Sql.CONVERSATIONS_RECENT)
    suspend fun recent(
        mode: String,
        limit: Int,
    ): List<ConversationWithLast>

    /**
     * All: alphabetically ordered by sender, keyset-paged on [cursor] (the last
     * sender of the previous page). A null cursor starts the walk.
     */
    @Query(Sql.CONVERSATIONS_ALL)
    suspend fun all(
        mode: String,
        cursor: String?,
        limit: Int,
    ): List<ConversationWithLast>

    /** The tab badge while conversations are shown. */
    @Query(Sql.CONVERSATIONS_COUNT_BY_MODE)
    fun observeCountByMode(mode: String): Flow<Int>

    /** One sender's messages, newest first, keyset-paged on `(timestamp, id)`. */
    @Query(Sql.EVENTS_THREAD_PAGE)
    suspend fun thread(
        sender: String,
        cursorTs: Long?,
        cursorId: Long,
        limit: Int,
    ): List<EventEntity>
}
