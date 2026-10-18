package com.personalos.app.data

import androidx.room.Dao
import androidx.room.Query

/**
 * The re-classify walk's two reads: a page of SMS rows below a cursor, and the
 * single-column write that replaces one row's category.
 *
 * Its own interface, like [RetagDao] and [RuleBackfillDao]: walking the store to
 * re-derive categories is a separate reason to change, and [EventDao]'s test
 * fakes stay untouched. The walk is by `id` (insertion order), ascending, so it
 * is resumable and never revisits a row.
 */
@Dao
interface CategoryBackfillDao {
    /** Up to [limit] SMS rows above [afterId], oldest first. */
    @Query("SELECT id, title, content FROM events WHERE source = 'sms' AND id > :afterId ORDER BY id ASC LIMIT :limit")
    suspend fun batch(
        afterId: Long,
        limit: Int,
    ): List<CategoryCandidate>

    /** Replace one row's category with the id the categorizer derived in Kotlin. */
    @Query("UPDATE events SET category = :category WHERE id = :id")
    suspend fun updateCategory(
        id: Long,
        category: String,
    )
}

/** The fields the categorizer needs: the sender is `events.title` for SMS. */
data class CategoryCandidate(
    val id: Long,
    val title: String,
    val content: String,
)
