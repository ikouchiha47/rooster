package com.personalos.app.ui.messages

import com.personalos.app.data.Conversation
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The conversations list's decisions, kept out of the composable so they can be
 * tested without rendering — the same split as [MessagesTab] and `WatchersModel`.
 *
 * There is one ordering — recency of the last message — shared by both views;
 * only the day grouping differs, and that is a layout decision the screen makes.
 */
class ConversationsModelTest {
    private fun conversation(
        sender: String,
        lastMessageId: Long,
        lastAt: Long = lastMessageId,
        msgCount: Int = 1,
        inboxCount: Int = 1,
        sentCount: Int = 0,
        otherCount: Int = 0,
        pinned: Int = 0,
        lastMessage: String? = null,
    ) = Conversation(
        source = "sms",
        sender = sender,
        lastAt = lastAt,
        lastMessageId = lastMessageId,
        msgCount = msgCount,
        inboxCount = inboxCount,
        sentCount = sentCount,
        otherCount = otherCount,
        pinned = pinned,
        lastMessage = lastMessage,
    )

    @Test
    fun `the timeline orders by newest last message first`() {
        val old = conversation("Alpha", lastMessageId = 1, lastAt = 100)
        val newest = conversation("Bravo", lastMessageId = 3, lastAt = 300)
        val middle = conversation("Charlie", lastMessageId = 2, lastAt = 200)

        val ordered = conversationTimeline(listOf(old, newest, middle))

        // One row per sender, recency of the last message, not message id order.
        assertEquals(listOf("Bravo", "Charlie", "Alpha"), ordered.map { it.sender })
    }

    @Test
    fun `the timeline breaks a lastAt tie by sender`() {
        val bravo = conversation("Bravo", lastMessageId = 1, lastAt = 500)
        val alpha = conversation("Alpha", lastMessageId = 2, lastAt = 500)

        // The keyset pages on (lastAt, sender), so the sort must tie-break the
        // same way or a page boundary could skip a row.
        assertEquals(listOf("Alpha", "Bravo"), conversationTimeline(listOf(bravo, alpha)).map { it.sender })
    }

    @Test
    fun `the label is the stored sender, never an invented name`() {
        assertEquals("VA-WBGBNK-S", conversationLabel(conversation("VA-WBGBNK-S", 1)))
        assertEquals("+919830611292", conversationLabel(conversation("+919830611292", 1)))
    }

    @Test
    fun `the preview states the real count, and unread only when there is any`() {
        val unread = conversation("A", 1, msgCount = 12, inboxCount = 3)
        val read = conversation("B", 2, msgCount = 1, inboxCount = 0)

        assertEquals("12 messages \u00b7 3 unread", conversationPreview(unread))
        assertEquals("1 message", conversationPreview(read))
    }

    @Test
    fun `the last message is the newest body, folded onto one line`() {
        val multiline =
            conversation(
                "A",
                1,
                lastMessage = "Hi Arjun,\n\nHere are the details you requested:\nACME-4821",
            )

        // The newlines an SMS body carries must not survive into a one-line row.
        assertEquals("Hi Arjun, Here are the details you requested: ACME-4821", conversationLastMessage(multiline))
    }

    @Test
    fun `whitespace around and inside the last message is normalised`() {
        assertEquals("Payment confirmed", conversationLastMessage(conversation("A", 1, lastMessage = "  Payment   confirmed \n")))
    }

    @Test
    fun `a conversation with no readable message falls back to the count line`() {
        // A LEFT JOIN can leave the text null; the row must not go blank.
        val noBody = conversation("A", 1, msgCount = 4, inboxCount = 2, lastMessage = null)
        assertEquals("4 messages \u00b7 2 unread", conversationLastMessage(noBody))

        val blank = conversation("B", 2, msgCount = 1, inboxCount = 0, lastMessage = "   \n ")
        assertEquals("1 message", conversationLastMessage(blank))
    }
}
