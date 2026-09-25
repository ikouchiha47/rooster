package com.personalos.app.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.SkipQueryVerification
import kotlinx.coroutines.flow.Flow

/**
 * Messages-scoped reads: the search queries, and the screen's change signal.
 *
 * Its own interface, like [RuleFireDao] and [BookmarkDao]: search is a separate
 * reason to change, and [EventDao]'s existing test fakes stay untouched.
 *
 * The `events_fts` table is created by the v19 migration, not declared as an
 * entity (Room cannot model an FTS5 external-content table with a custom
 * tokenizer), so both queries must skip Room's compile-time verification — it
 * has no entity to check them against. `MigrationSchemaTest` is what proves the
 * table and its triggers exist.
 */
@Dao
interface MessageSearchDao {
    /**
     * The Messages screen's change signal, scoped to SMS.
     *
     * The global `MAX(id)` ticks for a weather reading or an FX rate too, and a
     * screen that shows only messages must not be woken by another source's
     * writes — that is what made the list blank and refill during a feed sync.
     * It lives here rather than on [EventDao] for the same reason the search
     * reads do: [EventDao] is write-heavy and shared, and every one of its test
     * fakes would have had to grow a method it never calls.
     */
    @Query(Sql.EVENTS_MAX_ID_BY_SOURCE)
    fun observeMaxIdForSource(source: String): Flow<Long?>

    /** Infix sub-string match. [match] is a built FTS expression, never raw input. */
    @SkipQueryVerification
    @Query(Sql.EVENTS_SEARCH_MATCH)
    suspend fun searchMatch(
        match: String,
        limit: Int,
    ): List<EventEntity>

    /** Fallback for inputs too short for trigram; [pattern] is a literal LIKE pattern. */
    @SkipQueryVerification
    @Query(Sql.EVENTS_SEARCH_LIKE)
    suspend fun searchLike(
        pattern: String,
        limit: Int,
    ): List<EventEntity>
}
