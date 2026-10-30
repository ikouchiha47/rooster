package com.personalos.app.ui.calendar

import com.personalos.app.data.CalendarDateEntity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** One cell in the month grid. */
data class CalendarCell(
    val date: LocalDate,
    val inMonth: Boolean,
    val isToday: Boolean,
    val isSelected: Boolean,
    val items: List<CalendarDateEntity>,
    val plannerCount: Int = 0,
)

/**
 * A six-week month grid, Monday first. Six rows are always drawn so the
 * calendar does not jump height when a month starts on a Monday or spans six
 * weeks. Dates outside [month] are dimmed but still selectable.
 */
fun calendarGrid(
    month: YearMonth,
    rows: List<CalendarDateEntity>,
    selected: LocalDate,
    today: LocalDate,
    plannerCounts: Map<LocalDate, Int> = emptyMap(),
): List<CalendarCell> {
    val byDate = rows.groupBy { runCatching { LocalDate.parse(it.date) }.getOrNull() }
    val first = month.atDay(1)
    val start = first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    return (0 until 42).map { offset ->
        val date = start.plusDays(offset.toLong())
        CalendarCell(
            date = date,
            inMonth = YearMonth.from(date) == month,
            isToday = date == today,
            isSelected = date == selected,
            items = byDate[date].orEmpty().sortedWith(compareBy({ it.name }, { it.source })),
            plannerCount = plannerCounts[date] ?: 0,
        )
    }
}

/** The selected day's observances, in the same stable order the grid uses. */
fun agendaFor(
    rows: List<CalendarDateEntity>,
    date: LocalDate,
): List<CalendarDateEntity> =
    rows
        .filter { runCatching { LocalDate.parse(it.date) }.getOrNull() == date }
        .sortedWith(compareBy({ it.name }, { it.source }))

/** Month title: `October 2026`. English, like every other calendar label. */
fun monthTitle(month: YearMonth): String =
    "${month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} " +
        month.year

/** Prev/next month, with the day clamped to the target month's length. */
fun shiftMonth(
    month: YearMonth,
    selected: LocalDate,
    delta: Long,
): Pair<YearMonth, LocalDate> {
    val next = month.plusMonths(delta)
    val day = selected.dayOfMonth.coerceAtMost(next.lengthOfMonth())
    return next to next.atDay(day)
}
