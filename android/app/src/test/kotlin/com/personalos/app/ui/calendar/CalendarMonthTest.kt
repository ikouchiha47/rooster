package com.personalos.app.ui.calendar

import com.personalos.app.data.CalendarDateEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Month grouping and labelling of observances, tested without rendering. The
 * decisions pinned here: what a month header reads as, that rows are read
 * soonest first however they arrive, that a lone entry is still a month, and
 * the display cap — a UI decision, not the repository's window.
 */
class CalendarMonthTest {
    private fun obs(
        year: Int,
        month: Int,
        day: Int,
        name: String,
    ): CalendarDateEntity {
        val date = LocalDate.of(year, month, day)
        return CalendarDateEntity(
            source = "calendar:japan",
            feedUid = "$date-$name",
            region = "japan",
            kind = "national",
            date = date.toString(),
            startsAt = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            name = name,
            fetchedAt = 0L,
        )
    }

    // ------------------------------------------------------------- empty state

    @Test
    fun `no rows is no months`() {
        assertTrue(groupIntoCalendarMonths(emptyList()).isEmpty())
    }

    @Test
    fun `an empty state names what is missing`() {
        assertTrue(
            "with no regions the message must say to add one",
            calendarEmptyMessage(followedRegions = 0).contains("region", ignoreCase = true),
        )
        assertTrue(
            "with regions the message must say nothing is upcoming",
            calendarEmptyMessage(followedRegions = 2).contains("upcoming", ignoreCase = true),
        )
    }

    // ------------------------------------------------------------------ header

    @Test
    fun `a month header reads as a full month and year`() {
        assertEquals("MARCH 2027", calendarMonthHeader(2027, 3))
        assertEquals("DECEMBER 2026", calendarMonthHeader(2026, 12))
    }

    // ------------------------------------------------------------------ grouping

    @Test
    fun `observances group by month, soonest first however they arrive`() {
        val june = obs(2027, 6, 1, "June Day")
        val march = obs(2027, 3, 4, "Holi")
        val april = obs(2027, 4, 20, "April Day")
        val months = groupIntoCalendarMonths(listOf(june, march, april))

        assertEquals(listOf(3, 4, 6), months.map { it.month })
        assertEquals(listOf(2027, 2027, 2027), months.map { it.year })
        assertEquals(listOf("Holi"), months[0].items.map { it.name })
    }

    @Test
    fun `a month with a single entry is still a month`() {
        val months = groupIntoCalendarMonths(listOf(obs(2027, 3, 4, "Holi")))
        assertEquals(1, months.size)
        assertEquals(1, months[0].items.size)
        assertEquals(3, months[0].month)
    }

    @Test
    fun `entries inside a month keep the soonest first`() {
        val late = obs(2027, 3, 30, "Late March")
        val early = obs(2027, 3, 2, "Early March")
        val months = groupIntoCalendarMonths(listOf(late, early))
        assertEquals(listOf("Early March", "Late March"), months[0].items.map { it.name })
    }

    @Test
    fun `the same month across years is two groups, in year order`() {
        val nextYear = obs(2028, 3, 1, "Next year")
        val thisYear = obs(2027, 3, 1, "This year")
        val months = groupIntoCalendarMonths(listOf(nextYear, thisYear))
        assertEquals(listOf(2027, 2028), months.map { it.year })
    }

    // ------------------------------------------------------------------- cap

    @Test
    fun `the list is capped to the next six months`() {
        val rows = (1..7).map { month -> obs(2027, month, 1, "Month $month") }
        val months = groupIntoCalendarMonths(rows)
        assertEquals(CALENDAR_MONTHS_SHOWN, months.size)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), months.map { it.month })
    }

    @Test
    fun `the cap is a parameter, so a caller can ask for fewer`() {
        val rows = (1..3).map { month -> obs(2027, month, 1, "Month $month") }
        assertEquals(2, groupIntoCalendarMonths(rows, limit = 2).size)
    }

    @Test
    fun `a cap of zero shows nothing rather than crashing`() {
        assertTrue(groupIntoCalendarMonths(listOf(obs(2027, 3, 1, "Holi")), limit = 0).isEmpty())
    }

    @Test
    fun `the cap never drops a month that exists`() {
        val rows = (1..6).map { month -> obs(2027, month, 1, "Month $month") }
        assertEquals(6, groupIntoCalendarMonths(rows).size)
    }
}
