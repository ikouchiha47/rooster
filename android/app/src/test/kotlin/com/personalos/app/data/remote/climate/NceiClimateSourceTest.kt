package com.personalos.app.data.remote.climate

import com.personalos.app.core.climate.ClimateMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Fixture tests for the NCEI parser. The fixture keeps the `#` metadata block
 * and `Date,Departure` header, includes blank/`***` rows, and lists months out
 * of order to prove the parser sorts and rejects missing values.
 */
class NceiClimateSourceTest {
    private val fixture =
        """
        # Title: "Global Land and Ocean Average Temperature Departures"
        # Units: Degrees Celsius
        # Base Period: 1901-2000
        Date,Departure from Average
        202602,1.15
        202601,-0.20
        202609,1.33
        202610,
        202611,***
        188001,-0.39
        """.trimIndent()

    @Test
    fun `parses comments and header, skips missing values and orders months`() {
        val series = parseNceiCsv(fixture, fetchedAt = 2_000L)

        assertEquals(4, series.points.size)
        assertEquals(utcMonth(1880, 1), series.points.first().periodStart)
        assertEquals(utcMonth(2026, 9), series.points.last().periodStart)
        assertTrue(
            "points are ordered oldest to newest",
            series.points.zipWithNext().all { (a, b) -> a.periodStart < b.periodStart },
        )
        assertTrue(
            "blank and *** departures are absent",
            series.points.none { it.periodStart == utcMonth(2026, 10) || it.periodStart == utcMonth(2026, 11) },
        )
    }

    @Test
    fun `known latest row is an anomaly with UTC month bounds`() {
        val series = parseNceiCsv(fixture, fetchedAt = 2_000L)
        val latest = series.latest!!

        assertEquals(1.33, latest.value, 0.0)
        assertEquals(1.33, latest.anomaly!!, 0.0)
        assertEquals(utcMonth(2026, 9), latest.periodStart)
        assertEquals(utcMonth(2026, 10), latest.periodEnd)
    }

    @Test
    fun `series carries the NCEI metric, baseline and source identity`() {
        val series = parseNceiCsv(fixture, fetchedAt = 2_000L)

        assertEquals(ClimateMetrics.GLOBAL_TEMP_NCEI.id, series.metric)
        assertEquals("1901–2000", series.baseline)
        assertEquals("°C", series.unit)
        assertEquals("global", series.region)
        assertEquals(NceiClimateSource.ID, series.sourceId)
        assertEquals(NceiClimateSource.URL, series.sourceUrl)
        assertEquals(2_000L, series.fetchedAt)
        assertEquals(setOf(ClimateMetrics.GLOBAL_TEMP_NCEI.id), NceiClimateSource().metrics)
    }

    private fun utcMonth(
        year: Int,
        month: Int,
    ): Long =
        LocalDate
            .of(year, month, 1)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
}
