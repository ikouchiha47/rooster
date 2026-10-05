package com.personalos.app.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The user's time zone — the one owner of **"what day is it"**.
 *
 * Everything stored is a UTC instant (epoch millis), which is time-zone-free.
 * The zone is applied only at the edge, when an instant becomes a *day*, a
 * *time*, or a label. That is what stops the two clocks disagreeing: before this,
 * day buckets were raw epoch-days (UTC) while "Today" was the device's local
 * date, so around midnight the header said "Today · 25 Sept" on a phone that had
 * already ticked over to the 26th.
 *
 * **DST is handled for free** by [ZoneId]: a local day is not always 24 hours, so
 * day arithmetic goes through the zone (`LocalDate.plusDays`), never `+ DAY_MS`.
 *
 * A process-wide holder, like the notification center: the zone is app-wide state,
 * so a pure helper reads it rather than every call site threading it. The value is
 * persisted by the caller (see `ZoneRepository`) and restored on launch.
 */
object UserZone {
    @Volatile
    private var zone: ZoneId = ZoneId.systemDefault()

    val current: ZoneId get() = zone

    /** The zone id as persisted, e.g. `Asia/Kolkata`. */
    fun id(): String = zone.id

    /** Restores a persisted id; an unknown/blank id falls back to the device zone. */
    fun set(id: String?) {
        zone = id?.takeIf { it.isNotBlank() }?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
    }

    /** Start of the local day containing [instantMs], as epoch millis (UTC). */
    fun dayStart(instantMs: Long): Long =
        Instant
            .ofEpochMilli(instantMs)
            .atZone(zone)
            .toLocalDate()
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()

    /** Start of the local day [days] away from today (negative = past). DST-safe. */
    fun dayStartOffset(days: Long): Long =
        LocalDate
            .now(zone)
            .plusDays(days)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()

    /** Start of the local day after the one containing [instantMs]. DST-safe. */
    fun nextDayStart(instantMs: Long): Long =
        Instant
            .ofEpochMilli(instantMs)
            .atZone(zone)
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()

    /** The local calendar date of an instant. */
    fun localDate(instantMs: Long): LocalDate = Instant.ofEpochMilli(instantMs).atZone(zone).toLocalDate()
}
