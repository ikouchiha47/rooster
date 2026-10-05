package com.personalos.app.ui.common

import com.personalos.app.core.time.UserZone
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One day, in milliseconds. For *ranges* that are genuinely 24 hours; day
 * **buckets** never use it — a local day is not always 24h under DST, so they go
 * through [UserZone].
 */
const val DAY_MS = 86_400_000L

private val DAY_LABEL_FMT = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

private val WEEKDAY_FMT = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())

/**
 * Day bucket label: `Today` / `Yesterday` / `Mon 8 Sep`.
 *
 * The day is the **user's local day** ([UserZone]), so "Today" means the phone's
 * today, not UTC's — the two disagreed after local midnight before this.
 */
fun dayLabel(timestamp: Long): String {
    val day = UserZone.dayStart(timestamp)
    return when (day) {
        UserZone.dayStartOffset(0) -> "Today"
        UserZone.dayStartOffset(-1) -> "Yesterday"
        else -> DAY_LABEL_FMT.withZone(UserZone.current).format(Instant.ofEpochMilli(timestamp))
    }
}

/** Right-hand side of a day marker: the date, but only for Today/Yesterday. */
fun dayLabelRight(
    label: String,
    timestamp: Long,
): String =
    if (label == "Today" || label == "Yesterday") {
        DAY_LABEL_FMT.withZone(UserZone.current).format(Instant.ofEpochMilli(timestamp))
    } else {
        ""
    }

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
 * Sequential day-bucketing for a time-ordered list, in the user's zone.
 *
 * A new group starts wherever the **local** day changes, so input order is kept
 * and a day that arrives in two runs stays two groups. Pure, so it is unit-tested
 * without composing.
 */
fun <T> groupIntoDays(
    rows: List<T>,
    timestampOf: (T) -> Long,
): List<DayGroup<T>> {
    val out = mutableListOf<DayGroup<T>>()
    var current = Long.MIN_VALUE
    var bucket = mutableListOf<T>()
    for (row in rows) {
        val day = UserZone.dayStart(timestampOf(row))
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
 * The comparison is against the user's *local* today ([UserZone]), not the
 * device's, so a forecast for the 2nd reads `TODAY` when it is the 2nd where the
 * user is. Unparseable input falls back to the raw string rather than throwing.
 */
fun forecastDayLabel(isoDate: String): String {
    val date = runCatching { LocalDate.parse(isoDate) }.getOrNull() ?: return isoDate
    return if (date == LocalDate.now(UserZone.current)) "TODAY" else date.format(WEEKDAY_FMT).uppercase(Locale.getDefault())
}
