package com.personalos.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * The deferred-tag queue's three operations: enqueue a pending item, read the ones
 * that have since become ready, and drop the ones just tagged.
 *
 * Its own interface, like [RetagDao]: the queue is a separate reason to change from
 * the walk itself, and the walk's fakes stay untouched.
 */
@Dao
interface DeferredTagDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(rows: List<DeferredTagEntity>)

    /** Up to [limit] deferred items whose body is now usable (or finally absent). */
    @Query(Sql.DEFERRED_TAGS_READY)
    suspend fun ready(limit: Int): List<RetagCandidate>

    @Query(Sql.DEFERRED_TAGS_REMOVE)
    suspend fun remove(itemIds: List<String>)

    @Query(Sql.DEFERRED_TAGS_COUNT)
    suspend fun count(): Int
}
