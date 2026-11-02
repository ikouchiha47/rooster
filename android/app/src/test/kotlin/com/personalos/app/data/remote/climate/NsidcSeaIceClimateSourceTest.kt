package com.personalos.app.data.remote.climate

import com.personalos.app.core.climate.ClimateMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.YearMonth

class NsidcSeaIceClimateSourceTest {
    private val source = NsidcSeaIceClimateSource()

    @Test
    fun `parses extent and derives anomaly against the 1981-2010 mean`() {
        val series = source.parse(FIXTURE, ClimateMetrics.ARCTIC_ICE, ARCTIC_URL, fetchedAt = 42)!!

        assertEquals(4, series.points.size)
        assertEquals(10.0, series.points[0].value, 0.0001)
        assertEquals(-1.0, series.points[0].anomaly!!, 0.0001)
        assertEquals(12.0, series.points[1].value, 0.0001)
        assertEquals(1.0, series.points[1].anomaly!!, 0.0001)
    }

    @Test
    fun `latest extent and anomaly are the newest parsed month`() {
        val series = source.parse(FIXTURE, ClimateMetrics.ARCTIC_ICE, ARCTIC_URL, fetchedAt = 0)!!

        assertEquals(9.0, series.latest!!.value, 0.0001)
        assertEquals(-2.0, series.latest!!.anomaly!!, 0.0001)
        assertEquals(Instant.parse("2025-11-01T00:00:00Z").toEpochMilli(), series.latest!!.periodStart)
    }

    @Test
    fun `maps the file year and month to a UTC month period`() {
        val first = source.parse(FIXTURE, ClimateMetrics.ARCTIC_ICE, ARCTIC_URL, fetchedAt = 0)!!.points.first()

        assertEquals(Instant.parse("1981-11-01T00:00:00Z").toEpochMilli(), first.periodStart)
        assertEquals(Instant.parse("1981-12-01T00:00:00Z").toEpochMilli(), first.periodEnd)
    }

    @Test
    fun `skips malformed rows and rows outside the month range`() {
        val points = source.parse(FIXTURE, ClimateMetrics.ARCTIC_ICE, ARCTIC_URL, fetchedAt = 0)!!.points

        assertEquals(4, points.size)
        assertTrue(points.none { it.value.isNaN() })
        assertTrue(points.none { it.periodStart == Instant.parse("2026-11-01T00:00:00Z").toEpochMilli() })
    }

    @Test
    fun `no baseline years means a value with a null anomaly`() {
        val series = source.parse(NO_BASELINE, ClimateMetrics.ARCTIC_ICE, ARCTIC_URL, fetchedAt = 0)!!

        assertEquals(1, series.points.size)
        assertEquals(9.5, series.points[0].value, 0.0001)
        assertNull(series.points[0].anomaly)
    }

    @Test
    fun `describes the requested hemisphere and baseline`() {
        val series = source.parse(FIXTURE, ClimateMetrics.ANTARCTIC_ICE, ANTARCTIC_URL, fetchedAt = 7)!!

        assertEquals("nsidc-sea-ice", series.sourceId)
        assertEquals("sea_ice_antarctic", series.metric)
        assertEquals("antarctic", series.region)
        assertEquals("million km²", series.unit)
        assertEquals("1981–2010", series.baseline)
        assertEquals(ANTARCTIC_URL, series.sourceUrl)
        assertEquals(7, series.fetchedAt)
    }

    @Test
    fun `builds current-month URLs with a zero-padded month`() {
        val november = source.urls(YearMonth.of(2026, 11))
        val january = source.urls(YearMonth.of(2026, 1))

        assertEquals(ClimateMetrics.ARCTIC_ICE, november[0].first)
        assertTrue(november[0].second.endsWith("/north/monthly/data/N_11_extent_v4.0.csv"))
        assertEquals(ClimateMetrics.ANTARCTIC_ICE, november[1].first)
        assertTrue(november[1].second.endsWith("/south/monthly/data/S_11_extent_v4.0.csv"))

        assertTrue(january[0].second.endsWith("/N_01_extent_v4.0.csv"))
        assertTrue(january[1].second.endsWith("/S_01_extent_v4.0.csv"))
    }

    @Test
    fun `empty payload has no series`() {
        assertNull(source.parse("", ClimateMetrics.ARCTIC_ICE, ARCTIC_URL, fetchedAt = 0))
        assertNull(source.parse("# no header\n", ClimateMetrics.ARCTIC_ICE, ARCTIC_URL, fetchedAt = 0))
    }

    private companion object {
        const val ARCTIC_URL =
            "https://noaadata.apps.nsidc.org/NOAA/G02135/north/monthly/data/N_11_extent_v4.0.csv"
        const val ANTARCTIC_URL =
            "https://noaadata.apps.nsidc.org/NOAA/G02135/south/monthly/data/S_11_extent_v4.0.csv"

        const val FIXTURE =
            """year, mo,source_dataset, region, extent,   area
1981, 11,    NSIDC-0051,      N,  10.00,   8.00
1982, 11,    NSIDC-0051,      N,  12.00,   9.00
1983, 11,    NSIDC-0051,      N,  11.00,   8.50
2025, 11,    NSIDC-0803,      N,   9.00,   7.00
bad row
2026, 13,    NSIDC-0803,      N,   1.00,   1.00
2026, 11,    NSIDC-0803,      N,not-a-number, 1.00
2026, 11,    NSIDC-0803,      N
"""

        const val NO_BASELINE =
            """year, mo,source_dataset, region, extent,   area
2025, 11,    NSIDC-0803,      N,   9.50,   7.00
"""
    }
}
