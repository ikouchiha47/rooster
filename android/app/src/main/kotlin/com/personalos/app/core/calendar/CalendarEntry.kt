package com.personalos.app.core.calendar

/**
 * A dated observance from a calendar feed: a public holiday, a festival, or a
 * regional closure. One row, one date — the fact Travel's calendar and any
 * "within N days" rule read from.
 */
data class CalendarEntry(
    /** Feed-stable identity (`<date><region>@<host>`), so re-syncing upserts. */
    val uid: String,
    /** ISO `YYYY-MM-DD`, exactly as the feed states it. */
    val date: String,
    val name: String,
    val kind: CalendarKind,
)

/**
 * `YYYY-MM-DD` to epoch milliseconds at UTC midnight, so "within N days" is an
 * indexed range query rather than string parsing at read time. Pure: a bad date
 * is `null`, never a guess.
 */
object CalendarDates {
    fun toEpochMsUtc(isoDate: String): Long? {
        val parts = isoDate.split("-")
        if (parts.size != 3) return null
        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val day = parts[2].toIntOrNull() ?: return null
        if (month !in 1..12 || day !in 1..31) return null
        return runCatching {
            java.time.LocalDate
                .of(year, month, day)
                .atStartOfDay(java.time.ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        }.getOrNull()
    }
}

/** Where the observance applies. National covers everyone; regional is state-scoped. */
enum class CalendarKind(
    val serialName: String,
) {
    NATIONAL("national"),
    REGIONAL("regional"),
    ;

    companion object {
        fun from(value: String): CalendarKind? = entries.firstOrNull { it.serialName == value }
    }
}
