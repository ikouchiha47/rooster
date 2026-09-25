package com.personalos.app.ui.messages

import com.personalos.app.core.Chars
import com.personalos.app.data.Conversation

/*
 * The conversations list's decisions, kept out of the composable so they can be
 * tested without rendering — the same split as [MessagesTab] and `WatchersModel`.
 * The screen lays these out; it does not decide what they say.
 *
 * The two sections order differently on purpose:
 *
 *  - **Recent** is recency (pinned, then newest message id). Recency moves under
 *    writes, so this section is bounded and never cursored.
 *  - **All** is alphabetical by **sender**, because that order does not move when
 *    a message arrives, which is what lets it carry a stable keyset cursor.
 */

/** Recent order: pinned first, then the highest message id (the newest). */
val CONVERSATION_RECENT_ORDER: Comparator<Conversation> =
    compareByDescending<Conversation> { it.pinned }.thenByDescending { it.lastMessageId }

/** All order: pinned first, then sender A -> Z. Stable under writes. */
val CONVERSATION_ALL_ORDER: Comparator<Conversation> =
    compareByDescending<Conversation> { it.pinned }.thenBy { it.sender }

/** The two bodies the Messages list renders, each already ordered. */
data class ConversationSections(
    val recent: List<Conversation>,
    val all: List<Conversation>,
)

/**
 * Shapes the two reads the screen makes: the bounded recent page and the
 * complete (paged) all page. Both are re-ordered here rather than trusted from
 * SQL, so the list cannot disagree with the query about what "first" means and
 * the cap is re-applied defensively.
 */
fun conversationSections(
    recent: List<Conversation>,
    all: List<Conversation>,
    recentLimit: Int,
): ConversationSections =
    ConversationSections(
        recent = recent.sortedWith(CONVERSATION_RECENT_ORDER).take(recentLimit.coerceAtLeast(0)),
        all = all.sortedWith(CONVERSATION_ALL_ORDER),
    )

/**
 * Whether a conversation belongs in a box tab. `all` is every conversation;
 * the others need at least one message in that box, which is exactly the SQL
 * filter the reads use.
 */
fun conversationMatchesMode(
    conversation: Conversation,
    mode: String,
): Boolean =
    when (mode) {
        "inbox" -> conversation.inboxCount > 0
        "sent" -> conversation.sentCount > 0
        "other" -> conversation.otherCount > 0
        else -> true
    }

/**
 * The All section's keyset: the next page starts strictly after the last sender
 * of this one. A null cursor starts the walk; the cursor row itself is never
 * returned again.
 */
fun conversationAfterCursor(
    sender: String,
    cursor: String?,
): Boolean = cursor == null || sender > cursor

/** The label is the stored sender. No contacts permission, so no invented name. */
fun conversationLabel(conversation: Conversation): String = conversation.sender

/**
 * A one-line preview: the real message count, plus unread only when there is
 * any. "1 message" is spelled out, not "1 messages".
 */
fun conversationPreview(conversation: Conversation): String {
    val messages = if (conversation.msgCount == 1) "1 message" else "${conversation.msgCount} messages"
    return if (conversation.inboxCount > 0) {
        "$messages ${Chars.MIDDLE_DOT} ${conversation.inboxCount} unread"
    } else {
        messages
    }
}

/** Runs of whitespace (including the newlines SMS bodies carry) become one space. */
private val WHITESPACE_RUN = Regex("\\s+")

/**
 * The listing's second line: the **newest message** in the conversation, on one
 * line. SMS bodies are multi-line ("Hi,\n\nHere are the details…"), so the text
 * is folded before it is shown; the composable's `maxLines = 1` then only has to
 * ellipsize at the width, not choose between lines.
 *
 * Falls back to [conversationPreview] when there is no text — a `LEFT JOIN` can
 * produce a conversation whose message vanished between the counts and the text,
 * and the row must still say something true rather than go blank.
 */
fun conversationLastMessage(conversation: Conversation): String {
    val folded =
        conversation.lastMessage
            ?.replace(WHITESPACE_RUN, " ")
            ?.trim()
            .orEmpty()
    return folded.ifEmpty { conversationPreview(conversation) }
}
