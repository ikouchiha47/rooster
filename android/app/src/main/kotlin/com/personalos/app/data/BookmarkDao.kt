package com.personalos.app.data

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes for saved items.
 *
 * Its own interface rather than more methods on [EventDao], for the reason
 * [RuleFireDao] exists: [EventDao] is written by ingest and read by tiles, and
 * a Saved-view concern would be a third reason for it to change. Narrow here
 * also means every existing test fake stays untouched.
 *
 * A null `source` means "every saved item" — bookmarks are global; Messages'
 * Saved view is one scope over them.
 */
@Dao
interface BookmarkDao {
    /** The current stamp, so a toggle can flip it. */
    @Query(Sql.EVENT_BY_ULID)
    suspend fun byUlid(ulid: String): EventEntity?

    /** Writes the new stamp, or clears it with null. Returns rows touched. */
    @Query(Sql.EVENTS_SET_BOOKMARK)
    suspend fun setBookmark(
        ulid: String,
        bookmarkedAt: Long?,
    ): Int

    /** Saved items, newest save first, keyset-paged on the save stamp. */
    @Query(Sql.EVENTS_SAVED)
    suspend fun saved(
        source: String?,
        cursor: Long?,
        limit: Int,
    ): List<EventEntity>

    @Query(Sql.EVENTS_SAVED_COUNT)
    fun observeSavedCount(source: String?): Flow<Int>
}
