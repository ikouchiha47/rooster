package com.personalos.app.data.remote.climate

import com.personalos.app.core.climate.ClimateMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Fixture tests for the GISTEMP parser. The fixture keeps the real title line
 * and 18-column header, with rows deliberately out of order to prove the
 * parser sorts by month and drops `***` placeholders.
 */
class GistempClimateSourceTest {
    private val fixture =
        """
        Land-Ocean: Global Means
        Year,Jan,Feb,Mar,Apr,May,Jun,Jul,Aug,Sep,Oct,Nov,Dec,J-D,D-N,DJF,MAM,JJA,SON
        2026,1.08,1.23,1.30,1.16,1.13,1.17,1.24,1.42,1.38,***,***,***,***,***,1.12,1.20,1.28,***
        2025,1.37,1.25,1.36,1.24,1.07,1.06,1.02,1.17,1.24,1.18,1.21,1.05,1.18,1.20,1.29,1.22,1.08,1.21
        """.trimIndent()

    @Test
    fun `parses the header, skips the title and orders months`() {
        val series = parseGistempCsv(fixture, fetchedAt = 1_000L)

        assertEquals(21, series.points.size)
        assertEquals(utcMonth(2025, 1), series.points.first().periodStart)
        assertEquals(utcMonth(2026, 9), series.points.last().periodStart)
        assertTrue(
            "points are ordered oldest to newest",
            series.points.zipWithNext().all { (a, b) -> a.periodStart < b.periodStart },
        )
    }

    @Test
    fun `missing months are absent, never zero`() {
        val series = parseGistempCsv(fixture, fetchedAt = 1_000L)

        val october2026 =
            series.points.firstOrNull {
                it.periodStart == utcMonth(2026, 10)
            }
        assertEquals(null, october2026)
        assertTrue(series.points.none { it.value == 0.0 })
    }

    @Test
    fun `known latest row is an anomaly with UTC month bounds`() {
        val series = parseGistempCsv(fixture, fetchedAt = 1_000L)
        val latest = series.latest!!

        assertEquals(1.38, latest.value, 0.0)
        assertEquals(1.38, latest.anomaly!!, 0.0)
        assertEquals(utcMonth(2026, 9), latest.periodStart)
        assertEquals(utcMonth(2026, 10), latest.periodEnd)
    }

    @Test
    fun `series carries the GISTEMP metric, baseline and source identity`() {
        val series = parseGistempCsv(fixture, fetchedAt = 1_000L)

        assertEquals(ClimateMetrics.GLOBAL_TEMP.id, series.metric)
        assertEquals("1951–1980", series.baseline)
        assertEquals("°C", series.unit)
        assertEquals("global", series.region)
        assertEquals(GistempClimateSource.ID, series.sourceId)
        assertEquals(GistempClimateSource.URL, series.sourceUrl)
        assertEquals(1_000L, series.fetchedAt)
        assertEquals(setOf(ClimateMetrics.GLOBAL_TEMP.id), GistempClimateSource().metrics)
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
