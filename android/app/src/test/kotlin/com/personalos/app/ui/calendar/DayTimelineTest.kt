package com.personalos.app.ui.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class DayTimelineTest {
    @Test
    fun `the timeline is 48 half-hour lines with hour labels only`() {
        val slots = daySlots(LocalTime.of(8, 15))
        assertEquals(48, slots.size)
        assertEquals("08:00", slots[16].label)
        assertEquals("", slots[17].label)
        assertTrue(slots[16].isNow)
        assertFalse(slots[15].isNow)
    }

    @Test
    fun `the week strip starts on Monday and contains the selected day`() {
        val selected = LocalDate.of(2026, 10, 21)
        val week = weekDays(selected)
        assertEquals(7, week.size)
        assertEquals(LocalDate.of(2026, 10, 19), week.first())
        assertTrue(selected in week)
        assertEquals("WED", weekDayLetter(selected))
    }
}
