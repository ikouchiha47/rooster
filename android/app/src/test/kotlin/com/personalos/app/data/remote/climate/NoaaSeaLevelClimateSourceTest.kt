package com.personalos.app.data.remote.climate

import com.personalos.app.core.climate.ClimateMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class NoaaSeaLevelClimateSourceTest {
    private val source = NoaaSeaLevelClimateSource()

    @Test
    fun `skips the comment block and parses the date and satellite columns`() {
        val series = source.parse(FIXTURE, fetchedAt = 1_700_000_000_000)!!

        assertEquals(5, series.points.size)
        val first = series.points.first()
        assertEquals(Instant.parse("1992-12-01T00:00:00Z").toEpochMilli(), first.periodStart)
        assertEquals(Instant.parse("1993-01-01T00:00:00Z").toEpochMilli(), first.periodEnd)
        assertEquals(-16.93, first.value, 0.0001)
        assertNull(first.anomaly)
    }

    @Test
    fun `groups observations by month and averages them`() {
        val points = source.parsePoints(FIXTURE)

        val december = points.first { it.periodStart == Instant.parse("1992-12-01T00:00:00Z").toEpochMilli() }
        val january = points.first { it.periodStart == Instant.parse("1993-01-01T00:00:00Z").toEpochMilli() }
        val february = points.first { it.periodStart == Instant.parse("2002-02-01T00:00:00Z").toEpochMilli() }

        assertEquals(-16.93, december.value, 0.0001)
        assertEquals(-23.18, january.value, 0.0001)
        // Two overlapping missions in one row become that row's mean, not two votes.
        assertEquals(6.41, february.value, 0.0001)
    }

    @Test
    fun `maps decimal years to UTC calendar months at the boundaries`() {
        val points = source.parsePoints(MONTH_BOUNDARIES)

        assertEquals(
            listOf(
                Instant.parse("1992-12-01T00:00:00Z").toEpochMilli(),
                Instant.parse("1993-01-01T00:00:00Z").toEpochMilli(),
                Instant.parse("1993-02-01T00:00:00Z").toEpochMilli(),
                Instant.parse("1993-12-01T00:00:00Z").toEpochMilli(),
            ),
            points.map { it.periodStart },
        )
        assertEquals(listOf(1.0, 2.0, 3.0, 4.0), points.map { it.value })
    }

    @Test
    fun `latest value is the newest month, sorted from an unsorted payload`() {
        val series = source.parse(FIXTURE, fetchedAt = 0)!!

        assertEquals(Instant.parse("2025-01-01T00:00:00Z").toEpochMilli(), series.latest!!.periodStart)
        assertEquals(88.4, series.latest!!.value, 0.0001)
        assertEquals(Instant.parse("1992-12-01T00:00:00Z").toEpochMilli(), series.points.first().periodStart)
    }

    @Test
    fun `skips malformed dates, missing values and non-numeric values`() {
        val points = source.parsePoints(FIXTURE)

        assertEquals(5, points.size)
        assertTrue(points.none { it.value.isNaN() })
        assertTrue(points.none { it.periodStart == Instant.parse("1993-01-01T00:00:00Z").toEpochMilli() && it.value == 0.0 })
    }

    @Test
    fun `describes global sea level against the TOPEX Jason reference`() {
        val series = source.parse(FIXTURE, fetchedAt = 99)!!

        assertEquals("noaa-star-sea-level", series.sourceId)
        assertEquals(ClimateMetrics.SEA_LEVEL.id, series.metric)
        assertEquals("global", series.region)
        assertEquals("mm", series.unit)
        assertEquals("since 1993", series.baseline)
        assertEquals(NoaaSeaLevelClimateSource.URL, series.sourceUrl)
        assertEquals(99, series.fetchedAt)
    }

    @Test
    fun `empty or header-only payload has no series`() {
        assertNull(source.parse("", fetchedAt = 0))
        assertNull(source.parse("# title only\nyear,TOPEX/Poseidon,Jason-1,Jason-2,Jason-3,Sentinel-6MF\n", fetchedAt = 0))
    }

    private companion object {
        const val FIXTURE =
            """#title = mean sea level anomaly global ocean (66S to 66N) (Annual signals retained)
#institution = NOAA/Laboratory for Satellite Altimetry
#trend = 3.17 mm/year (no glacial isostatic adjustment correction)
year,TOPEX/Poseidon,Jason-1,Jason-2,Jason-3,Sentinel-6MF
1992.96140,-14.63000,,,,
1992.98650,-19.23000,,,,
1993.01230,-22.03000,,,,
1993.04060,-24.33000,,,,
1993.07000,,,,,
1993.08000,not-a-number,,,,
1994.99950,,,,,89.58000
garbage,-1,,,,
2002.16260,7.27000,5.55000,,,
2025.02130,,,,,88.40000
"""

        const val MONTH_BOUNDARIES =
            """year,TOPEX/Poseidon,Jason-1,Jason-2,Jason-3,Sentinel-6MF
1992.99950,1.00000,,,,
1993.00050,2.00000,,,,
1993.12680,3.00000,,,,
1993.99999,4.00000,,,,
"""
    }
}
