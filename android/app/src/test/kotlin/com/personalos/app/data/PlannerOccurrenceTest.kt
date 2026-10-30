package com.personalos.app.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

class PlannerOccurrenceTest {
    private fun item(
        date: LocalDate,
        rule: String? = null,
        allDay: Boolean = true,
    ) = PlannerItemEntity(
        id = "item",
        workspaceId = "personal",
        kind = "event",
        title = "Birthday",
        startAt = date.atTime(if (allDay) LocalTime.MIDNIGHT else LocalTime.of(9, 30)).toInstant(ZoneOffset.UTC).toEpochMilli(),
        endAt = if (allDay) null else date.atTime(10, 30).toInstant(ZoneOffset.UTC).toEpochMilli(),
        allDay = allDay,
        timeZone = "UTC",
        recurrenceRule = rule,
        createdAt = 0,
        updatedAt = 0,
    )

    @Test
    fun `a one-off appears only on its own date`() {
        val day = LocalDate.of(2026, 10, 21)
        val found = plannerOccurrences(item(day), day.minusDays(1), day.plusDays(1), ZoneOffset.UTC)
        assertEquals(listOf(day), found.map { it.date })
    }

    @Test
    fun `a yearly birthday repeats without moving the original date`() {
        val born = LocalDate.of(1990, 10, 21)
        val from = LocalDate.of(2026, 1, 1)
        val to = LocalDate.of(2026, 12, 31)
        val found = plannerOccurrences(item(born, "FREQ=YEARLY"), from, to, ZoneOffset.UTC)
        assertEquals(listOf(LocalDate.of(2026, 10, 21)), found.map { it.date })
    }

    @Test
    fun `a timed weekly event keeps its local time`() {
        val start = LocalDate.of(2026, 10, 5)
        val found =
            plannerOccurrences(
                item(start, "FREQ=WEEKLY", allDay = false),
                start,
                start.plusWeeks(1),
                ZoneOffset.UTC,
            )
        assertEquals(2, found.size)
        assertEquals(LocalTime.of(9, 30), found.first().start?.toLocalTime())
    }
}
