package com.personalos.app.ui.messages

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * [formatMessageTime] is the one owner of a listing row's time label, so it is
 * tested here without rendering. Every case pins "now" and the zone, so the
 * result does not depend on when or where the test runs.
 */
class MessageTimeTest {
    private val zone: ZoneId = ZoneId.of("UTC")

    private fun millis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 0,
        minute: Int = 0,
    ): Long =
        LocalDateTime
            .of(year, month, day, hour, minute)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    @Test
    fun `a message from today shows only the clock`() {
        val now = millis(2026, 6, 29, hour = 14, minute = 5)
        val ts = millis(2026, 6, 29, hour = 9, minute = 30)

        assertEquals("09:30", formatMessageTime(now, ts, zone))
    }

    @Test
    fun `same day across midnight is still the clock, not a date`() {
        val now = millis(2026, 6, 29, hour = 0, minute = 5)
        val ts = millis(2026, 6, 28, hour = 23, minute = 59)

        // Different calendar day, so this is NOT "today": it is yesterday.
        assertEquals("28 Jun", formatMessageTime(now, ts, zone))
    }

    @Test
    fun `an earlier day in the same year shows day and month`() {
        val now = millis(2026, 6, 29, hour = 14, minute = 5)
        val ts = millis(2026, 6, 1, hour = 9, minute = 30)

        assertEquals("1 Jun", formatMessageTime(now, ts, zone))
    }

    @Test
    fun `a different year shows the full date`() {
        val now = millis(2026, 6, 29)
        val ts = millis(2025, 6, 29)

        assertEquals("29 Jun 2025", formatMessageTime(now, ts, zone))
    }

    @Test
    fun `a year boundary is a different year even one day apart`() {
        val now = millis(2026, 1, 1, hour = 0, minute = 30)
        val ts = millis(2025, 12, 31, hour = 23, minute = 30)

        assertEquals("31 Dec 2025", formatMessageTime(now, ts, zone))
    }

    @Test
    fun `the zone decides the day, not UTC`() {
        // 31 Dec 2025 20:00 UTC is already 1 Jan 2026 in Kolkata (+05:30).
        val ts = millis(2025, 12, 31, hour = 20, minute = 0)
        val now = millis(2026, 6, 29, hour = 12, minute = 0)

        val kolkata = ZoneId.of("Asia/Kolkata")
        assertEquals("1 Jan", formatMessageTime(now, ts, kolkata))
        assertEquals("31 Dec 2025", formatMessageTime(now, ts, zone))
    }
}
