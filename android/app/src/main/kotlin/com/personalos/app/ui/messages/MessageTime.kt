package com.personalos.app.ui.messages

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * A message listing row shows a date, not a bare clock: a time alone reads
 * fine for today's messages and lies about last week's. The rule, in order:
 *
 *   same local day   -> "HH:mm"          (e.g. "14:05")
 *   same local year  -> "d MMM"          (e.g. "29 Jun")
 *   older            -> "d MMM yyyy"     (e.g. "29 Jun 2026")
 *
 * The zone and the "now" are parameters, never read from the ambient clock
 * here, so the function is pure and a test can pin the year boundary.
 *
 * The app's UI is English-only, so the locale is pinned rather than taken from
 * the device: a month name that changed with the phone's locale would make the
 * one output that is not a number non-deterministic.
 */
private val TIME_ONLY: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

private val SAME_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

private val OLDER: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

/**
 * Formats an epoch-millisecond timestamp for a message listing, relative to
 * [nowMillis] in [zone].
 *
 * Both instants are resolved through [zone] before comparison, so "same day"
 * and "same year" are the user's calendar day/year, not UTC's. DST and the year
 * boundary fall out of [ZonedDateTime] for free.
 *
 * @param nowMillis the instant "today" is measured from.
 * @param tsMillis the message's instant.
 * @param zone the user's zone; the one place a stored instant becomes a label.
 */
fun formatMessageTime(
    nowMillis: Long,
    tsMillis: Long,
    zone: ZoneId,
): String {
    val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
    val ts = Instant.ofEpochMilli(tsMillis).atZone(zone)
    return when {
        now.toLocalDate() == ts.toLocalDate() -> TIME_ONLY.format(ts)
        now.year == ts.year -> SAME_YEAR.format(ts)
        else -> OLDER.format(ts)
    }
}

/**
 * "When" for the picker's Unlisted rows: `today 14:05`, `yesterday`, else the same
 * day-listing rule as [formatMessageTime] (`29 Jun`, `29 Jun 2026`).
 */
fun lastInteractionLabel(
    nowMillis: Long,
    tsMillis: Long,
    zone: ZoneId,
): String {
    val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
    val ts = Instant.ofEpochMilli(tsMillis).atZone(zone)
    return when {
        now.toLocalDate() == ts.toLocalDate() -> "today ${TIME_ONLY.format(ts)}"
        now.toLocalDate().minusDays(1) == ts.toLocalDate() -> "yesterday"
        else -> formatMessageTime(nowMillis, tsMillis, zone)
    }
}

/** Date **and** time for an interaction row: `18 Oct · 13:43`, or with the year. */
fun interactionStamp(
    nowMillis: Long,
    tsMillis: Long,
    zone: ZoneId,
): String {
    val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
    val ts = Instant.ofEpochMilli(tsMillis).atZone(zone)
    val date = if (now.year == ts.year) SAME_YEAR.format(ts) else OLDER.format(ts)
    return "$date ${'\u00B7'} ${TIME_ONLY.format(ts)}"
}
