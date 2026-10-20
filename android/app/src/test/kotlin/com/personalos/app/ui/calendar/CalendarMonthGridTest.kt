package com.personalos.app.ui.calendar

import com.personalos.app.data.CalendarDateEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class CalendarMonthGridTest {
    private fun row(
        name: String,
        date: String,
        source: String = "calendar:test:india",
    ) = CalendarDateEntity(
        source = source,
        feedUid = "$source:$name",
        region = "india",
        kind = "national",
        date = date,
        startsAt = 0L,
        name = name,
        fetchedAt = 0L,
    )

    @Test
    fun `the grid is six weeks and starts on the Monday before the first`() {
        val cells = calendarGrid(YearMonth.of(2026, 10), emptyList(), LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 21))
        assertEquals(42, cells.size)
        assertEquals(LocalDate.of(2026, 9, 28), cells.first().date)
        assertEquals(LocalDate.of(2026, 11, 8), cells.last().date)
        assertTrue(cells.first().date.dayOfWeek == java.time.DayOfWeek.MONDAY)
    }

    @Test
    fun `a date carries every observance and the agenda keeps the same order`() {
        val rows = listOf(row("Zed", "2026-10-21"), row("Alpha", "2026-10-21"), row("Other", "2026-10-22"))
        val cells = calendarGrid(YearMonth.of(2026, 10), rows, LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 21))
        val day = cells.single { it.date == LocalDate.of(2026, 10, 21) }
        assertEquals(listOf("Alpha", "Zed"), day.items.map { it.name })
        assertTrue(day.isSelected)
        assertEquals(listOf("Alpha", "Zed"), agendaFor(rows, LocalDate.of(2026, 10, 21)).map { it.name })
    }

    @Test
    fun `the year overview counts every month and keeps its rows`() {
        val rows = listOf(row("Zed", "2026-10-21"), row("Alpha", "2026-10-02"), row("Other", "2026-12-25"))
        val months = yearMonths(2026, rows)
        assertEquals(12, months.size)
        assertEquals(2, months[9].count)
        assertEquals(listOf("Alpha", "Zed"), months[9].items.map { it.name })
        assertEquals("Other", months[11].first?.name)
        assertEquals(0, months[0].count)
    }

    @Test
    fun `month shift clamps the selected day`() {
        val (month, selected) = shiftMonth(YearMonth.of(2026, 1), LocalDate.of(2026, 1, 31), 1)
        assertEquals(YearMonth.of(2026, 2), month)
        assertEquals(LocalDate.of(2026, 2, 28), selected)
    }
}
