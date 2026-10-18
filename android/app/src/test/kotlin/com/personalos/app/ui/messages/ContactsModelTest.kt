package com.personalos.app.ui.messages

import com.personalos.app.core.sms.CallInteraction
import com.personalos.app.core.sms.Contact
import com.personalos.app.data.SenderLast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactsModelTest {
    @Test
    fun bucketIsLetterOrSymbolsCaseInsensitive() {
        assertEquals("B", bucketOf("Babu"))
        assertEquals("I", bucketOf("ibbu"))
        assertEquals("A", bucketOf("abhi"))
        assertEquals("A", bucketOf("ABHI"))
        assertEquals("#", bucketOf("25"))
        assertEquals("#", bucketOf("+91 98765"))
        assertEquals("#", bucketOf(""))
    }

    @Test
    fun listedIsSymbolsThenLetters() {
        val saved =
            listOf(
                Contact("Babu", "2"),
                Contact("25", "1"),
                Contact("Anil", "3"),
                Contact("ibbu", "4"),
            )

        val items = listedItems(saved)

        assertEquals(listOf("#", "A", "B", "I"), items.filterIsInstance<PickerItem.Header>().map { it.text })
        assertEquals(listOf("25", "Anil", "Babu", "ibbu"), items.filterIsInstance<PickerItem.Entry>().map { it.entry.primary })
    }

    @Test
    fun letterIndexPointsAtEachHeading() {
        val items = listedItems(listOf(Contact("Anil", "3"), Contact("Babu", "2")))
        val index = letterIndex(items)
        assertEquals(0, index["A"])
        assertEquals(2, index["B"])
    }

    @Test
    fun emptyListedHasNoItems() {
        assertTrue(listedItems(emptyList()).isEmpty())
    }

    @Test
    fun messagedExcludesSavedAndSortsByRecency() {
        val senders = listOf(SenderLast("+911", 10), SenderLast("+912", 30), SenderLast("+913", 20))
        val rows = unlistedRows(senders, emptyList(), setOf("+913"), UnlistedChannel.MESSAGED)
        assertEquals(listOf("+912", "+911"), rows.map { it.number })
    }

    @Test
    fun attendedAndMissedSplitCalls() {
        val calls =
            listOf(
                CallInteraction("+911", 10, attended = true, missed = false),
                CallInteraction("+912", 20, attended = false, missed = true),
                CallInteraction("+913", 30, attended = true, missed = true),
            )
        assertEquals(
            listOf("+913", "+911"),
            unlistedRows(emptyList(), calls, emptySet(), UnlistedChannel.ATTENDED).map { it.number },
        )
        assertEquals(
            listOf("+913", "+912"),
            unlistedRows(emptyList(), calls, emptySet(), UnlistedChannel.MISSED).map { it.number },
        )
    }

    @Test
    fun selectedNumbersFollowPickerOrderIncludingUnlisted() {
        val items =
            listedItems(listOf(Contact("Anil", "3"), Contact("Babu", "2"))) +
                listOf(PickerItem.Entry(PickerEntry("+91999", "today", "+91999", saved = false)))

        assertEquals(listOf("3", "+91999"), selectedNumbers(items, setOf("+91999", "3")))
    }
}
