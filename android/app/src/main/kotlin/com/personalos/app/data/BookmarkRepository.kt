package com.personalos.app.data

import com.personalos.app.core.bookmark.isSaved
import com.personalos.app.core.bookmark.nextBookmark
import kotlinx.coroutines.flow.Flow

/**
 * Saved items: one writer, one reader, over the flag on the item itself.
 *
 * The rule (what a toggle does; what "saved" means) lives in
 * [com.personalos.app.core.bookmark] and is tested there. This reads the
 * current stamp, applies that rule, writes it back, and answers with the new
 * state so the caller never has to guess or re-read.
 *
 * Read-then-write rather than one SQL `CASE`: it keeps a single definition of
 * the rule. The cost is a race if two writers toggled the same item at the same
 * instant — impossible here, since the only writer is a user's swipe on one
 * screen of a single-user app.
 */
class BookmarkRepository(
    private val dao: BookmarkDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Flips saved state. Returns the state after the toggle; false for an unknown item. */
    suspend fun toggle(ulid: String): Boolean {
        val current = dao.byUlid(ulid) ?: return false
        val next = nextBookmark(current.bookmarkedAt, now())
        dao.setBookmark(ulid, next)
        return isSaved(next)
    }

    /** Saved items, newest save first. [source] null means every saved item. */
    suspend fun saved(
        source: String? = null,
        cursor: Long? = null,
        limit: Int = DEFAULT_PAGE,
    ): List<EventEntity> = dao.saved(source, cursor, limit)

    /** How many are saved in a scope — the tab's badge. */
    fun observeSavedCount(source: String? = null): Flow<Int> = dao.observeSavedCount(source)

    companion object {
        /** One page of saved items; the UI pages further on the save stamp. */
        const val DEFAULT_PAGE = 200
    }
}
