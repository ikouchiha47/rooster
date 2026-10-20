package com.personalos.app.ui.calendar

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** One 30-minute line in the day timeline. */
data class DaySlot(
    /** Minutes from midnight, 0..1410. */
    val startMinute: Int,
    /** `08:00` on the hour, blank on the half hour. */
    val label: String,
    /** True when the current time falls inside this half-hour. */
    val isNow: Boolean,
)

/** 48 half-hour lines, with the current one marked. */
fun daySlots(now: LocalTime?): List<DaySlot> =
    (0 until 48).map { index ->
        val minute = index * 30
        val slotNow =
            now != null &&
                now.hour * 60 + now.minute in minute until (minute + 30)
        DaySlot(
            startMinute = minute,
            label = if (minute % 60 == 0) "%02d:00".format(minute / 60) else "",
            isNow = slotNow,
        )
    }

/** The Monday-to-Sunday strip around the selected date. */
fun weekDays(selected: LocalDate): List<LocalDate> {
    val monday = selected.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    return (0 until 7).map { monday.plusDays(it.toLong()) }
}

/** `MON` for the week strip. */
fun weekDayLetter(date: LocalDate): String =
    date.dayOfWeek
        .getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH)
        .uppercase(Locale.ENGLISH)
        .take(3)
