package com.personalos.app.ui.calendar

import com.personalos.app.data.CalendarDateEntity
import java.time.Instant
import java.time.Month
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale

/**
 * How many months ahead the observances list shows.
 *
 * A UI decision, deliberately not the repository's: the repository returns the
 * whole 12-month window, and the screen caps what it draws so the list stays
 * scannable. Keeping the cap here means it is testable without rendering.
 */
const val CALENDAR_MONTHS_SHOWN = 6

/** One calendar month of observances, in reading order. */
data class CalendarMonth(
    val year: Int,
    /** 1..12, so the header can be rebuilt without re-parsing a date. */
    val month: Int,
    val items: List<CalendarDateEntity>,
)

/**
 * Header for a month: `MARCH 2027`.
 *
 * English, like every other label in the app, so the string is stable rather
 * than drifting with the device locale.
 */
fun calendarMonthHeader(
    year: Int,
    month: Int,
): String {
    val name = Month.of(month).getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    return "${name.uppercase(Locale.ENGLISH)} $year"
}

/**
 * Group observances by calendar month, soonest first, capped to [limit] months.
 *
 * Ordering is display, so it lives here rather than in the query: rows are
 * sorted by [CalendarDateEntity.startsAt] (name as the tie-break) before
 * grouping, so a caller cannot be surprised by the order it passed in. The
 * month a row belongs to is derived from `starts_at` at UTC, which is the same
 * form the entity documents `date` in.
 *
 * A [limit] of zero or less draws nothing; the cap drops whole months, never
 * entries, so a shown month is never half a month.
 */
fun groupIntoCalendarMonths(
    rows: List<CalendarDateEntity>,
    limit: Int = CALENDAR_MONTHS_SHOWN,
): List<CalendarMonth> {
    if (limit <= 0) return emptyList()
    val sorted = rows.sortedWith(compareBy({ it.startsAt }, { it.name }))
    val out = mutableListOf<CalendarMonth>()
    var current: YearMonth? = null
    var bucket = mutableListOf<CalendarDateEntity>()
    for (row in sorted) {
        val month = YearMonth.from(Instant.ofEpochMilli(row.startsAt).atZone(ZoneOffset.UTC))
        if (bucket.isNotEmpty() && month != current) {
            out.add(CalendarMonth(current!!.year, current.monthValue, bucket))
            if (out.size == limit) return out
            bucket = mutableListOf()
        }
        current = month
        bucket.add(row)
    }
    if (bucket.isNotEmpty()) {
        out.add(CalendarMonth(current!!.year, current.monthValue, bucket))
    }
    return out
}

/**
 * The honest empty state: "add a region" and "nothing upcoming" are different
 * facts, and a single "no data" would hide which one the user is looking at.
 */
fun calendarEmptyMessage(followedRegions: Int): String =
    if (followedRegions == 0) {
        "No regions followed yet — add one above to see its holidays and festivals."
    } else {
        "Nothing upcoming in the next $CALENDAR_MONTHS_SHOWN months."
    }
