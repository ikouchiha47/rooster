package com.personalos.app.ui.messages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarTest {
    // ----------------------------------------------------------- avatarInitials

    @Test
    fun `a contact name uses the initials of its first two words`() {
        assertEquals("AR", avatarInitials("Ananya Rao"))
        assertEquals("VS", avatarInitials("vikram singh"))
    }

    @Test
    fun `a single-word name uses its first two letters`() {
        assertEquals("AN", avatarInitials("Ananya"))
        assertEquals("A", avatarInitials("A"))
    }

    @Test
    fun `a DLT header uses the brand token`() {
        assertEquals("BS", avatarInitials("BV-BSNLWB-S"))
        assertEquals("AM", avatarInitials("AX-AMZNIN"))
    }

    @Test
    fun `a bare number uses its last two digits`() {
        assertEquals("07", avatarInitials("918056428007"))
        assertEquals("07", avatarInitials("+91 80564 28007"))
        assertEquals("12", avatarInitials("12"))
    }

    @Test
    fun `an empty or unrenderable label gets a placeholder`() {
        assertEquals("?", avatarInitials(""))
        assertEquals("?", avatarInitials("   "))
        assertEquals("?", avatarInitials("---"))
        assertEquals("?", avatarInitials("🙂"))
    }

    // -------------------------------------------------------- avatarColorIndex

    @Test
    fun `the colour index is deterministic`() {
        val size = 9
        val once = avatarColorIndex("918056428007", size)
        repeat(50) {
            assertEquals(once, avatarColorIndex("918056428007", size))
        }
    }

    @Test
    fun `the colour index is always inside the palette`() {
        val size = 9
        val keys = listOf("Ananya Rao", "BV-BSNLWB-S", "918056428007", "", "AX-AMZNIN")
        for (key in keys) {
            val index = avatarColorIndex(key, size)
            assertTrue("index $index out of range for \"$key\"", index in 0 until size)
        }
    }

    @Test
    fun `the same sender keeps the same colour across a palette`() {
        assertEquals(
            avatarColorIndex("BV-BSNLWB-S", 9),
            avatarColorIndex("BV-BSNLWB-S", 9),
        )
    }

    @Test
    fun `a non-positive palette size collapses to zero`() {
        assertEquals(0, avatarColorIndex("Ananya Rao", 0))
        assertEquals(0, avatarColorIndex("Ananya Rao", -3))
    }
}
