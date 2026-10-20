package com.personalos.app.ui.calendar

import com.personalos.app.data.CalendarDateEntity
import java.time.LocalDate
import java.time.YearMonth

/** The three views the calendar mockup defines. */
enum class CalendarMode(
    val label: String,
) {
    DAY("Day"),
    MONTH("Month"),
    YEAR("Year"),
}

/** One month in the year overview: how many dates it holds and the first one. */
data class MiniMonth(
    val month: YearMonth,
    val count: Int,
    val first: CalendarDateEntity?,
    val items: List<CalendarDateEntity>,
)

/** Twelve month summaries, each keeping its own first observance. */
fun yearMonths(
    year: Int,
    rows: List<CalendarDateEntity>,
): List<MiniMonth> {
    val byMonth =
        rows
            .mapNotNull { row ->
                val date = runCatching { LocalDate.parse(row.date) }.getOrNull() ?: return@mapNotNull null
                YearMonth.from(date) to row
            }.groupBy({ it.first }, { it.second })
    return (1..12).map { m ->
        val month = YearMonth.of(year, m)
        val items = byMonth[month].orEmpty().sortedWith(compareBy({ it.startsAt }, { it.name }))
        MiniMonth(month = month, count = items.size, first = items.firstOrNull(), items = items)
    }
}
