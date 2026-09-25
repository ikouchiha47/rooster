package com.personalos.app.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity

/**
 * One row per conversation, maintained by triggers on `events` (v20).
 *
 * The primary key is `(source, sender)`: a sender is only a sender within a
 * source, so the pair is the identity. `source` is always `'sms'` for now (the
 * triggers and the backfill are scoped to it), but it is part of the key so a
 * later source can group its own events without a schema change.
 *
 * The per-box counts are stored, not derived on read: a tab filters
 * conversations in SQL (`inbox_count > 0`), which cannot be done against an
 * ungrouped `events` scan cheaply.
 *
 * There are **no indices**: the composite primary key is the lookup path
 * (`(source, sender)` for one conversation, and the All section's `sender`
 * walk is served by the primary key's index).
 */
@Entity(
    tableName = "conversations",
    primaryKeys = ["source", "sender"],
)
data class ConversationEntity(
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "sender") val sender: String,
    @ColumnInfo(name = "last_at") val lastAt: Long,
    @ColumnInfo(name = "last_message_id") val lastMessageId: Long,
    @ColumnInfo(name = "msg_count") val msgCount: Int,
    @ColumnInfo(name = "inbox_count") val inboxCount: Int,
    @ColumnInfo(name = "sent_count") val sentCount: Int,
    @ColumnInfo(name = "other_count") val otherCount: Int,
    @ColumnInfo(name = "pinned") val pinned: Int,
) {
    fun toDomain(): Conversation =
        Conversation(
            source = source,
            sender = sender,
            lastAt = lastAt,
            lastMessageId = lastMessageId,
            msgCount = msgCount,
            inboxCount = inboxCount,
            sentCount = sentCount,
            otherCount = otherCount,
            pinned = pinned,
        )
}

/**
 * A `conversations` row plus the text of its newest message, read by joining
 * `events` on `last_message_id` (`last_content`). Not an entity: it is a read
 * shape, and `events.content` stays owned by `events`.
 */
data class ConversationWithLast(
    @Embedded val conversation: ConversationEntity,
    @ColumnInfo(name = "last_content") val lastContent: String?,
) {
    fun toDomain(): Conversation = conversation.toDomain().copy(lastMessage = lastContent)
}
