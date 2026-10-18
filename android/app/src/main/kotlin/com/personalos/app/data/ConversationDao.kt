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
    /** Per-category conversation counts, for the tab strip badges. */
    @Query(Sql.CONVERSATIONS_COUNTS_BY_CATEGORY)
    fun observeCountsByCategory(): Flow<List<CategoryCount>>

    /** Every SMS sender and their latest message time, most recently active first. */
    @Query(Sql.CONVERSATIONS_SENDERS)
    suspend fun sendersWithLast(): List<SenderLast>

    /**
     * The Timeline: recency-ordered conversations whose latest message carries
     * [categoryId], one row per sender, keyset-paged on `(lastAt, sender)`. A
     * null cursor starts the walk.
     */
    @Query(Sql.CONVERSATIONS_TIMELINE)
    suspend fun timeline(
        categoryId: String,
        cursorAt: Long?,
        cursorSender: String?,
        limit: Int,
    ): List<ConversationWithLast>

    /** One sender's messages, newest first, keyset-paged on `(timestamp, id)`. */
    @Query(Sql.EVENTS_THREAD_PAGE)
    suspend fun thread(
        sender: String,
        cursorTs: Long?,
        cursorId: Long,
        limit: Int,
    ): List<EventEntity>
}

/** One SMS sender and the time of their latest message — the picker's "last seen". */
data class SenderLast(
    val sender: String,
    val lastAt: Long,
    val sim: String? = null,
)
