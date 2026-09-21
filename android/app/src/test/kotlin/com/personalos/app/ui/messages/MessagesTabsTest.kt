package com.personalos.app.ui.messages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Messages tab strip as data. The distinction that matters: four tabs filter
 * SMS by which box it sits in, and Saved is a different read entirely — so the
 * screen never has to carry a special case of its own.
 */
class MessagesTabsTest {
    @Test
    fun `the strip reads in the order it is shown`() {
        assertEquals(
            listOf("All", "Alerts", "Digest", "Muted", "Saved"),
            MessagesTab.entries.map { it.label },
        )
    }

    @Test
    fun `each box tab names its mode`() {
        assertEquals("all", MessagesTab.ALL.mode())
        assertEquals("inbox", MessagesTab.ALERTS.mode())
        assertEquals("sent", MessagesTab.DIGEST.mode())
        assertEquals("other", MessagesTab.MUTED.mode())
    }

    @Test
    fun `saved is not a box, so it has no mode`() {
        assertNull(MessagesTab.SAVED.mode())
        assertEquals(
            "saved is the only tab that is not a box filter",
            listOf(MessagesTab.SAVED),
            MessagesTab.entries.filter { it.mode() == null },
        )
    }

    @Test
    fun `a tab index outside the strip reads as All rather than crashing`() {
        assertTrue(messagesTabAt(-1) == MessagesTab.ALL)
        assertTrue(messagesTabAt(99) == MessagesTab.ALL)
        assertTrue(messagesTabAt(4) == MessagesTab.SAVED)
    }
}
