package com.personalos.app.data.remote.climate

import com.personalos.app.core.climate.ClimateMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The SOI file is fixed-width, three header lines, and fills unpublished
 * months with `-999.9`. Missing months must be absent, not zero.
 */
class CpcSoiClimateSourceTest {
    private val source = CpcSoiClimateSource(now = { FIXED_NOW })

    private fun series(raw: String) = source.parse(raw, FIXED_NOW).single()

    @Test
    fun `parses a full year into twelve monthly points`() {
        val parsed =
            series(
                HEADER +
                    row(
                        1951,
                        listOf("2.5", "1.5", "-0.2", "-0.5", "-1.1", "0.3", "-1.7", "-0.4", "-1.8", "-1.6", "-1.3", "-1.2"),
                    ),
            )

        assertEquals(12, parsed.points.size)

        val january = parsed.points.first()
        assertEquals(utc(1951, 1), january.periodStart)
        assertEquals(utc(1951, 2), january.periodEnd)
        assertEquals(2.5, january.value, 0.0001)
        assertNull(january.anomaly)

        val december = parsed.points.last()
        assertEquals(utc(1951, 12), december.periodStart)
        assertEquals(utc(1952, 1), december.periodEnd)
        assertEquals(-1.2, december.value, 0.0001)
    }

    @Test
    fun `drops missing-value sentinels and keeps the latest real month`() {
        val parsed =
            series(
                HEADER +
                    row(2025, List(12) { "0.5" }) +
                    "\n" +
                    row(2026, listOf("1.1", "1.4", "1.2", "-0.6", "-0.9", "-1.4", "-2.4", "-1.1", "-2.0", "-999.9", "-999.9", "-999.9")) +
                    "\n" +
                    row(2027, List(12) { "-999.9" }),
            )

        assertEquals(21, parsed.points.size)
        assertEquals(utc(2026, 9), parsed.latest?.periodStart)
        assertEquals(utc(2026, 10), parsed.latest?.periodEnd)
        assertEquals(-2.0, parsed.latest?.value ?: error("missing latest"), 0.0001)
    }

    @Test
    fun `sorts rows chronologically`() {
        val parsed =
            series(
                HEADER +
                    row(1952, List(12) { "1.0" }) +
                    "\n" +
                    row(1950, List(12) { "2.0" }) +
                    "\n" +
                    row(1951, List(12) { "3.0" }),
            )

        assertEquals(36, parsed.points.size)
        assertEquals(utc(1950, 1), parsed.points.first().periodStart)
        assertEquals(utc(1951, 1), parsed.points[12].periodStart)
        assertEquals(utc(1952, 1), parsed.points[24].periodStart)
        assertEquals(utc(1952, 12), parsed.latest?.periodStart)
    }

    @Test
    fun `skips malformed rows and bad months but keeps valid data`() {
        val withBadFebruary =
            listOf("0.5", "n/a", "-0.3", "0.3", "-2.8", "0.2", "-0.0", "-2.0", "-2.1", "0.1", "-0.5", "-0.8")

        val parsed =
            source.parse(
                """
                SOI TABLE
                YEAR   JAN   FEB   MAR   APR   MAY   JUN   JUL   AUG   SEP   OCT   NOV   DEC
                1953
                not a year
                ${row(1954, withBadFebruary)}

                ${row(1955, List(12) { "1.0" })}
                """,
                FIXED_NOW,
            )

        val series = parsed.single()
        assertEquals(23, series.points.size)
        assertTrue(series.points.none { it.periodStart == utc(1954, 2) })
        assertEquals(utc(1955, 12), series.latest?.periodStart)
    }

    @Test
    fun `blank or header-only payload yields no series`() {
        assertTrue(source.parse("", FIXED_NOW).isEmpty())
        assertTrue(source.parse("  \n\n ", FIXED_NOW).isEmpty())
        assertTrue(source.parse(HEADER, FIXED_NOW).isEmpty())
    }

    @Test
    fun `series carries the SOI metric metadata`() {
        val series = series(HEADER + row(1951, List(12) { "0.0" }))

        assertEquals(ClimateMetrics.SOI.id, series.metric)
        assertEquals(ClimateMetrics.SOI.label, series.label)
        assertEquals("global", series.region)
        assertEquals("index", series.unit)
        assertEquals("1935–present", series.baseline)
        assertEquals("noaa-cpc-soi", series.sourceId)
        assertEquals(CpcSoiClimateSource.URL, series.sourceUrl)
        assertEquals(FIXED_NOW, series.fetchedAt)
    }

    /** Builds one fixed-width row exactly as CPC lays it out: 6 chars per month. */
    private fun row(
        year: Int,
        values: List<String>,
    ): String = year.toString().padStart(4) + values.joinToString("") { it.padStart(6) }

    private fun utc(
        year: Int,
        month: Int,
    ): Long =
        LocalDate
            .of(year, month, 1)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()

    private companion object {
        const val FIXED_NOW = 1_800_000_000_000L

        const val HEADER =
            """
            (STAND TAHITI - STAND DARWIN)  SEA LEVEL PRESS
                                    ANOMALY

            YEAR   JAN   FEB   MAR   APR   MAY   JUN   JUL   AUG   SEP   OCT   NOV   DEC
            """
    }
}
