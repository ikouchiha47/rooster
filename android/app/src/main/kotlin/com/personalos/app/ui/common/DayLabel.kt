package com.personalos.app.ui.common

import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/** One day, in milliseconds. */
const val DAY_MS = 86_400_000L

private val DAY_LABEL_FMT = SimpleDateFormat("EEE d MMM", Locale.getDefault())

private val WEEKDAY_FMT = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())

/**
 * Day bucket label: `Today` / `Yesterday` / `Mon 8 Sep`.
 *
 * One definition for every timeline in the app. Radar and Messages still carry
 * private copies of this from before it was shared - they should be folded into
 * this file so there is a single source.
 */
fun dayLabel(timestamp: Long): String {
    val bucket = timestamp / DAY_MS
    val today = System.currentTimeMillis() / DAY_MS
    return when (bucket) {
        today -> "Today"
        today - 1 -> "Yesterday"
        else -> DAY_LABEL_FMT.format(Date(timestamp))
    }
}

/** Right-hand side of a day marker: the date, but only for Today/Yesterday. */
fun dayLabelRight(
    label: String,
    timestamp: Long,
): String = if (label == "Today" || label == "Yesterday") DAY_LABEL_FMT.format(Date(timestamp)) else ""

/**
 * One day bucket of an already time-ordered timeline, newest first.
 *
 * Display grouping only: the list underneath is unchanged, so batched loads
 * keyed off pages or ids keep working while headers come and go.
 */
data class DayGroup<T>(
    val dayStart: Long,
    val items: List<T>,
)

/**
 * Sequential day-bucketing for a time-ordered list.
 *
 * A new group starts wherever the [DAY_MS] bucket changes, so input order is
 * kept and a day that arrives in two runs (it cannot, the lists are ordered,
 * but cheap to be safe) stays two groups rather than being merged across
 * other days. Pure, so it is unit-tested without composing.
 */
fun <T> groupIntoDays(
    rows: List<T>,
    timestampOf: (T) -> Long,
): List<DayGroup<T>> {
    val out = mutableListOf<DayGroup<T>>()
    var current = Long.MIN_VALUE
    var bucket = mutableListOf<T>()
    for (row in rows) {
        val day = timestampOf(row) / DAY_MS * DAY_MS
        if (bucket.isNotEmpty() && day != current) {
            out.add(DayGroup(current, bucket))
            bucket = mutableListOf()
        }
        current = day
        bucket.add(row)
    }
    if (bucket.isNotEmpty()) out.add(DayGroup(current, bucket))
    return out
}

/**
 * Short label for a forecast row, from an ISO date: `TODAY`, else `THU`.
 *
 * Weather rows are one line per day and the date is already implied by position,
 * so only the weekday earns the space. Unparseable input falls back to the raw
 * string rather than throwing.
 */
fun forecastDayLabel(isoDate: String): String {
    val date = runCatching { LocalDate.parse(isoDate) }.getOrNull() ?: return isoDate
    return if (date == LocalDate.now()) "TODAY" else date.format(WEEKDAY_FMT).uppercase(Locale.getDefault())
}
