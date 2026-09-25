package com.personalos.app.data

/**
 * One conversation: a sender, and the facts about its messages.
 *
 * Materialised in the `conversations` table (v20) rather than grouped on read:
 * grouping scans `events`, and this screen reads the grouped shape on every
 * open. [sender] is the address **as stored** in `events.title` — for SMS that
 * column is the sender — and is never resolved against a contact, because the
 * app has no contacts permission and inventing a name would be a second owner
 * of the fact.
 *
 * [lastMessageId] is the monotonically increasing `events.id` of the newest
 * message and is the sort/cursor key for the Recent section; [lastAt] is the
 * newest `timestamp`, which is what the row displays. They can differ when an
 * item arrives carrying an older publish time.
 *
 * [lastMessage] is that newest message's text, read by joining `events` on
 * [lastMessageId]. It is not stored in `conversations` — the table keeps the
 * message's *identity*, and the text has one owner, the `events` row — so the
 * listing can preview it without a second query and without copying the body.
 * Nullable because the join is a `LEFT JOIN`: a conversation is only ever
 * without a message if its rows were deleted between the counts and the text.
 */
data class Conversation(
    val source: String,
    val sender: String,
    val lastAt: Long,
    val lastMessageId: Long,
    val msgCount: Int,
    val inboxCount: Int,
    val sentCount: Int,
    val otherCount: Int,
    val pinned: Int,
    val lastMessage: String? = null,
)
