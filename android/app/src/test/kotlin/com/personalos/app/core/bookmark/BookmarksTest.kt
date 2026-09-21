package com.personalos.app.core.bookmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The only rule bookmarking has: a toggle flips the state, and "saved" is a
 * stamp, not a flag. Pure, so the Saved view and the toggle cannot disagree
 * about what saved means.
 */
class BookmarksTest {
    @Test
    fun `toggling an unsaved item saves it at that moment`() {
        assertEquals(1_726_531_200_000L, nextBookmark(current = null, now = 1_726_531_200_000L))
    }

    @Test
    fun `toggling a saved item unsaves it`() {
        assertNull(nextBookmark(current = 1_726_531_200_000L, now = 1_726_600_000_000L))
    }

    @Test
    fun `two toggles return to the original state`() {
        val saved = nextBookmark(current = null, now = 1_000L)
        val unsaved = nextBookmark(current = saved, now = 2_000L)
        assertNull("saved then unsaved is unsaved", unsaved)
    }

    @Test
    fun `an item saved at epoch zero is still saved`() {
        // The trap: a stamp of 0 is falsy-looking and must not read as unsaved.
        assertTrue(isSaved(0L))
        assertFalse(isSaved(null))
    }

    @Test
    fun `any stamp means saved, whatever its value`() {
        assertTrue(isSaved(1L))
        assertTrue(isSaved(Long.MAX_VALUE))
    }

    @Test
    fun `saving again overwrites the stamp with the newer moment`() {
        val first = nextBookmark(current = null, now = 1_000L)
        val second = nextBookmark(current = null, now = 9_000L)
        assertEquals(1_000L, first)
        assertEquals(9_000L, second)
    }
}
