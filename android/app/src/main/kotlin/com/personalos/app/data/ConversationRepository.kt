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
    /**
     * The conversation list for [categoryId]: every conversation whose latest
     * message carries that category, one row per sender, newest first. Paged by
     * the `(lastAt, sender)` keyset of the previous page; a null cursor starts
     * the walk. Recency moves under writes, so this is bounded by [limit] and
     * never loads the set whole.
     */
    suspend fun timeline(
        categoryId: String,
        cursorAt: Long?,
        cursorSender: String?,
        limit: Int,
    ): List<Conversation> = dao.timeline(categoryId, cursorAt, cursorSender, limit).map(ConversationWithLast::toDomain)

    /** The tab badges: conversation counts per category. */
    fun observeCountsByCategory(): Flow<List<CategoryCount>> = dao.observeCountsByCategory()

    /** Every SMS sender and their latest message time, most recently active first. */
    suspend fun sendersWithLast(): List<SenderLast> = dao.sendersWithLast()

    /** One sender's messages, newest first, keyset-paged on `(timestamp, id)`. */
    suspend fun thread(
        sender: String,
        cursorTs: Long?,
        cursorId: Long,
        limit: Int,
    ): List<EventEntity> = dao.thread(sender, cursorTs, cursorId, limit)
}
