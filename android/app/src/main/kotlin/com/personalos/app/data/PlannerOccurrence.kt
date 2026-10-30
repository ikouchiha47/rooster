package com.personalos.app.data
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** One dated appearance of a planner item, after recurrence expansion. */
data class PlannerOccurrence(
    val item: PlannerItemEntity,
    val date: LocalDate,
    val start: LocalDateTime?,
    val end: LocalDateTime?,
)

/**
 * The item's dates intersecting [from]..[to]. Recurrence is the small RFC 5545
 * subset the planner stores: DAILY, WEEKLY, MONTHLY, YEARLY. Exceptions and
 * BYDAY are deliberately absent until the editor supports them.
 */
fun plannerOccurrences(
    item: PlannerItemEntity,
    from: LocalDate,
    to: LocalDate,
    zone: ZoneId,
): List<PlannerOccurrence> {
    val start = Instant.ofEpochMilli(item.startAt).atZone(zone)
    val startDate = start.toLocalDate()
    val startTime = if (item.allDay) null else start.toLocalTime()
    val duration = item.endAt?.let { it - item.startAt }
    val frequency = FREQ.find(item.recurrenceRule.orEmpty())?.groupValues?.get(1)
    val until =
        item.recurrenceUntil
            ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            ?.coerceAtMost(to)
    val last = until ?: to
    if (startDate > last) return emptyList()

    val dates = mutableListOf<LocalDate>()
    var date = startDate
    var count = 0
    while (date <= last) {
        if (item.recurrenceCount != null && count >= item.recurrenceCount) break
        if (date >= from) dates += date
        count += 1
        date =
            when (frequency) {
                "DAILY" -> date.plusDays(1)
                "WEEKLY" -> date.plusWeeks(1)
                "MONTHLY" -> date.plusMonths(1)
                "YEARLY" -> date.plusYears(1)
                else -> break
            }
        if (frequency == null) break
    }
    return dates.distinct().map { day ->
        val occurrenceStart = startTime?.let { LocalDateTime.of(day, it) }
        PlannerOccurrence(
            item = item,
            date = day,
            start = occurrenceStart,
            end = occurrenceStart?.plusNanos((duration ?: 0L) * 1_000_000L),
        )
    }
}

private val FREQ = Regex("""FREQ=([A-Z]+)""")
