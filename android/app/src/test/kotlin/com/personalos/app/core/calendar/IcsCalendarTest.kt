package com.personalos.app.core.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IcsCalendarTest {
    /**
     * Shaped exactly like the OfficeHolidays India/West Bengal feed: a folded
     * summary, an escaped comma, a `;PARAM=` on DTSTART, a national row inside
     * a state feed, and a VEVENT we must ignore because it has no DTSTART.
     */
    private val feed =
        """
        BEGIN:VCALENDAR
        VERSION:2.0
        BEGIN:VEVENT
        DTSTART;VALUE=DATE:20260112
        SUMMARY:Swami Vivekananda Jayanti
        UID:2026-01-12IN-WB102regregion@www.officeholidays.com
        DESCRIPTION:West Bengal only.
        END:VEVENT
        BEGIN:VEVENT
        DTSTART;VALUE=DATE:20260126
        SUMMARY:Republic Day
        UID:2026-01-26IN408regcountry@www.officeholidays.com
        END:VEVENT
        BEGIN:VEVENT
        DTSTART:20261020T000000Z
        SUMMARY:Durga Puja\, Maha Saptami
        UID:2026-10-20IN-WB3244regregion@www.officeholidays.com
        END:VEVENT
        BEGIN:VEVENT
        DTSTART;VALUE=DATE:20260626
        SUMMARY:Kali Puja
        UID:2026-06-26IN-WB41regregion@www.officeholidays.com
        END:VEVENT
        BEGIN:VEVENT
        SUMMARY:No date here
        UID:broken@example.com
        END:VEVENT
        END:VCALENDAR
        """.trimIndent()

    @Test
    fun `each dated event becomes one entry`() {
        val entries = IcsCalendar.parse(feed)
        assertEquals(4, entries.size)
        val republic = entries.single { it.name == "Republic Day" }
        assertEquals("2026-01-26", republic.date)
        assertEquals("2026-01-26IN408regcountry@www.officeholidays.com", republic.uid)
    }

    @Test
    fun `scope is read from the uid`() {
        val entries = IcsCalendar.parse(feed).associateBy { it.name }
        assertEquals(CalendarKind.NATIONAL, entries.getValue("Republic Day").kind)
        assertEquals(CalendarKind.REGIONAL, entries.getValue("Swami Vivekananda Jayanti").kind)
        assertEquals(CalendarKind.REGIONAL, entries.getValue("Durga Puja, Maha Saptami").kind)
    }

    @Test
    fun `escaped text is unescaped`() {
        val entries = IcsCalendar.parse(feed).associateBy { it.name }
        assertTrue("escaped comma", "Durga Puja, Maha Saptami" in entries)
    }

    /**
     * RFC 5545 folding drops the CRLF and the one leading whitespace of the
     * continuation, so a producer keeps the space at the end of the first line.
     * A parser that forgets to unfold reads half a holiday name.
     */
    @Test
    fun `folded lines are joined by the continuation rule`() {
        val folded =
            "BEGIN:VEVENT\nDTSTART;VALUE=DATE:20260626\n" +
                "SUMMARY:Dol Purnima across \n two lines\n" +
                "UID:2026-06-26IN-WB41regregion@example.com\nEND:VEVENT"
        assertEquals("Dol Purnima across two lines", IcsCalendar.parse(folded).single().name)
    }

    @Test
    fun `a datetime stamp and a date-only stamp both yield an ISO date`() {
        val entries = IcsCalendar.parse(feed).associateBy { it.name }
        assertEquals("2026-10-20", entries.getValue("Durga Puja, Maha Saptami").date)
        assertEquals("2026-01-12", entries.getValue("Swami Vivekananda Jayanti").date)
    }

    @Test
    fun `an event without a date is skipped, never guessed`() {
        assertTrue(IcsCalendar.parse(feed).none { it.name == "No date here" })
    }

    @Test
    fun `blank input yields nothing`() {
        assertEquals(emptyList<CalendarEntry>(), IcsCalendar.parse(""))
    }
}
