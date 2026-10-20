package com.personalos.app.ui.calendar

import com.personalos.app.data.CalendarDateEntity

/** One namespace's rows on a date. */
data class AgendaGroup(
    val source: String,
    val rows: List<CalendarDateEntity>,
)

/**
 * Group agenda rows by namespace. The namespace header carries the region, so
 * a row does not repeat "India / West Bengal" on every line.
 */
fun agendaGroups(rows: List<CalendarDateEntity>): List<AgendaGroup> =
    rows
        .groupBy { it.source }
        .map { (source, items) -> AgendaGroup(source, items.sortedWith(compareBy({ it.name }, { it.feedUid }))) }
        .sortedBy { it.source }

/**
 * The provider's event title without the region it already carries:
 * `West Bengal: Vijaya Dashami (Regional Holiday)` -> `Vijaya Dashami`.
 *
 * This is presentation only; the feed's name stays the stored fact.
 */
fun calendarDisplayName(
    name: String,
    regionLabel: String?,
): String {
    val shortRegion = regionLabel?.substringAfterLast(" / ")
    var value = name.trim()
    if (!shortRegion.isNullOrBlank()) {
        value = value.removePrefix("$shortRegion: ")
    }
    value = value.replace(Regex("""\s*\((Regional|National) Holiday\)\s*$"""), "")
    return value.ifBlank { name }
}
