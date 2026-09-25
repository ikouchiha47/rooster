package com.personalos.app.data

import kotlinx.coroutines.flow.Flow

/**
 * The one read path behind the Messages screen's conversation list and thread.
 *
 * It maps rows to the [Conversation] domain model and owns nothing itself: the
 * facts live in the `conversations` table, which the triggers keep true. `limit`
 * is the caller's, because bounding a list is a display decision
 * (docs/CODE-DESIGN-GUIDELINES.md §1).
 */
class ConversationRepository(
    private val dao: ConversationDao,
) {
    /** Recent conversations for [mode], newest message first, bounded. */
    suspend fun recent(
        mode: String,
        limit: Int,
    ): List<Conversation> = dao.recent(mode, limit).map(ConversationWithLast::toDomain)

    /** A page of all conversations for [mode], sender A -> Z after [cursor]. */
    suspend fun all(
        mode: String,
        cursor: String?,
        limit: Int,
    ): List<Conversation> = dao.all(mode, cursor, limit).map(ConversationWithLast::toDomain)

    /** The tab badge while conversations are shown. */
    fun observeCountByMode(mode: String): Flow<Int> = dao.observeCountByMode(mode)

    /** One sender's messages, newest first, keyset-paged on `(timestamp, id)`. */
    suspend fun thread(
        sender: String,
        cursorTs: Long?,
        cursorId: Long,
        limit: Int,
    ): List<EventEntity> = dao.thread(sender, cursorTs, cursorId, limit)
}
