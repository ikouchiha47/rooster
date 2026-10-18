package com.personalos.app.ui.messages

import com.personalos.app.core.Chars
import com.personalos.app.data.Conversation

/*
 * The conversations list's decisions, kept out of the composable so they can be
 * tested without rendering — the same split as [MessageTab] and `WatchersModel`.
 * The screen lays these out; it does not decide what they say.
 *
 * There is one ordering: **recency** of the last message. Both views read the
 * same [ConversationRepository.timeline] source and differ only in whether the
 * rows are bucketed under day markers.
 */

/**
 * Timeline order: newest last-message first, `sender` as the tiebreak. This is
 * the same ordering (and the same `(lastAt, sender)` key) the Timeline query
 * pages on, so a merged list cannot disagree with the store about "newest".
 */
val CONVERSATION_TIMELINE_ORDER: Comparator<Conversation> =
    compareByDescending<Conversation> { it.lastAt }.thenBy { it.sender }

/**
 * The conversation list: conversations newest-last-message first, re-applied
 * here rather than trusted from SQL. Grouping into day buckets is
 * [com.personalos.app.ui.common.groupIntoDays]; the row stays a conversation, so
 * the view is one row per sender.
 */
fun conversationTimeline(conversations: List<Conversation>): List<Conversation> = conversations.sortedWith(CONVERSATION_TIMELINE_ORDER)

/**
 * The fallback label: the stored sender, verbatim. The row overlays it with a
 * known contact name; when contacts are unavailable this is what shows, so the
 * list is never blank and never invents a name.
 */
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
