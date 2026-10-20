package com.personalos.app.ui.calendar

import com.personalos.app.data.CalendarDateEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarAgendaTest {
    private fun row(
        name: String,
        source: String = "calendar:officeholidays:india/west-bengal",
    ) = CalendarDateEntity(
        source = source,
        feedUid = "$source:$name",
        region = "india/west-bengal",
        kind = "regional",
        date = "2026-10-21",
        startsAt = 0L,
        name = name,
        fetchedAt = 0L,
    )

    @Test
    fun `the region prefix and holiday suffix are removed from the title`() {
        assertEquals(
            "Vijaya Dashami",
            calendarDisplayName("West Bengal: Vijaya Dashami (Regional Holiday)", "India / West Bengal"),
        )
        assertEquals("Diwali", calendarDisplayName("Diwali", null))
    }

    @Test
    fun `agenda is grouped by namespace and keeps one header per source`() {
        val groups =
            agendaGroups(
                listOf(
                    row("A", "calendar:google:india"),
                    row("B", "calendar:google:india"),
                    row("C", "calendar:officeholidays:india/west-bengal"),
                ),
            )
        assertEquals(
            listOf("calendar:google:india", "calendar:officeholidays:india/west-bengal"),
            groups.map { it.source },
        )
        assertEquals(listOf("A", "B"), groups.first().rows.map { it.name })
    }
}
