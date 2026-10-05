package com.personalos.app.core.time

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The one owner of "what day is it". These are the cases the old UTC-epoch-day
 * bucketing got wrong: a non-UTC user, and a DST day that is not 24 hours.
 */
class UserZoneTest {
    @After
    fun reset() {
        UserZone.set(null) // back to the device zone
    }

    @Test
    fun `an IST day starts at local midnight, not UTC midnight`() {
        UserZone.set("Asia/Kolkata")
        // 2026-09-26 00:30 IST is still 2026-09-25 in UTC — the bug's whole point.
        val instant = Instant.parse("2026-09-25T19:00:00Z").toEpochMilli()

        assertEquals(LocalDate.of(2026, 9, 26), UserZone.localDate(instant))
        assertEquals(
            "local midnight is 18:30Z the day before",
            Instant.parse("2026-09-25T18:30:00Z").toEpochMilli(),
            UserZone.dayStart(instant),
        )
    }

    @Test
    fun `a DST day is 23 hours, so day arithmetic cannot be plus-24h`() {
        UserZone.set("America/New_York")
        // 2026-03-08: US clocks spring forward at 02:00, so the local day is 23h.
        val instant = Instant.parse("2026-03-08T15:00:00Z").toEpochMilli()

        val start = UserZone.dayStart(instant)
        val next = UserZone.nextDayStart(instant)

        assertEquals(23L * 3_600_000L, next - start)
    }

    @Test
    fun `an unknown zone id falls back to the device zone`() {
        UserZone.set("Not/AZone")
        assertEquals(ZoneId.systemDefault(), UserZone.current)
    }

    @Test
    fun `today's offset is the local start of today`() {
        UserZone.set("Asia/Kolkata")
        assertEquals(UserZone.dayStart(System.currentTimeMillis()), UserZone.dayStartOffset(0))
    }
}
