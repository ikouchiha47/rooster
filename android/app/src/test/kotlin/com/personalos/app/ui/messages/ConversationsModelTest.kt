package com.personalos.app.ui.messages

import com.personalos.app.data.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The conversations list's decisions, kept out of the composable so they can be
 * tested without rendering — the same split as [MessagesTab] and `WatchersModel`.
 *
 * The two sections have deliberately different orderings. Recent is recency
 * (pinned, then newest message); All is alphabetical by **sender**, because that
 * order does not move under writes and so can carry a keyset cursor.
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
    fun `sections order recent by recency and all by sender`() {
        val conversations =
            listOf(
                conversation("Charlie", lastMessageId = 1),
                conversation("Alpha", lastMessageId = 2),
                conversation("Bravo", lastMessageId = 3),
            )

        val sections = conversationSections(recent = conversations, all = conversations, recentLimit = 2)

        // Recent: newest message first, bounded to the limit.
        assertEquals(listOf("Bravo", "Alpha"), sections.recent.map { it.sender })
        // All: A -> Z, complete.
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), sections.all.map { it.sender })
    }

    @Test
    fun `a pinned conversation leads both sections`() {
        val conversations =
            listOf(
                conversation("Alpha", lastMessageId = 9),
                conversation("Zulu", lastMessageId = 1, pinned = 1),
            )

        val sections = conversationSections(conversations, conversations, recentLimit = 2)

        assertEquals(listOf("Zulu", "Alpha"), sections.recent.map { it.sender })
        assertEquals(listOf("Zulu", "Alpha"), sections.all.map { it.sender })
    }

    @Test
    fun `a tab filters by the box that has messages`() {
        val alert = conversation("A", 1, inboxCount = 1, sentCount = 0, otherCount = 0)
        val digest = conversation("B", 2, inboxCount = 0, sentCount = 1, otherCount = 0)
        val muted = conversation("C", 3, inboxCount = 0, sentCount = 0, otherCount = 2)

        assertTrue(conversationMatchesMode(alert, "all"))
        assertTrue(conversationMatchesMode(digest, "all"))
        assertTrue(conversationMatchesMode(alert, "inbox"))
        assertFalse("a sent-only conversation is not in Alerts", conversationMatchesMode(digest, "inbox"))
        assertTrue(conversationMatchesMode(digest, "sent"))
        assertFalse(conversationMatchesMode(alert, "sent"))
        assertTrue(conversationMatchesMode(muted, "other"))
        assertFalse(conversationMatchesMode(muted, "inbox"))
    }

    @Test
    fun `the cursor is a strict sender comparison`() {
        assertTrue("a null cursor starts the walk", conversationAfterCursor("Alpha", null))
        assertTrue(conversationAfterCursor("Bravo", "Alpha"))
        assertFalse("the cursor row itself is not returned again", conversationAfterCursor("Alpha", "Alpha"))
        assertFalse(conversationAfterCursor("Aardvark", "Alpha"))
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
