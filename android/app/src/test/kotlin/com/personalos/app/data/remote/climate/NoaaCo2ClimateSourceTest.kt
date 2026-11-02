package com.personalos.app.data.remote.climate

import com.personalos.app.core.climate.ClimateMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class NoaaCo2ClimateSourceTest {
    private val source = NoaaCo2ClimateSource()

    @Test
    fun `skips comments and finds the header before parsing data`() {
        val series = source.parse(FIXTURE, fetchedAt = 1_700_000_000_000)

        assertEquals(4, series?.points?.size)
        val first = series!!.points.first()
        assertEquals(Instant.parse("1958-03-01T00:00:00Z").toEpochMilli(), first.periodStart)
        assertEquals(Instant.parse("1958-04-01T00:00:00Z").toEpochMilli(), first.periodEnd)
        assertEquals(315.71, first.value, 0.0001)
        assertNull(first.anomaly)
    }

    @Test
    fun `maps year and month to a UTC month period`() {
        val january = source.parse(FIXTURE, fetchedAt = 0)!!.points[2]

        assertEquals(Instant.parse("1959-01-01T00:00:00Z").toEpochMilli(), january.periodStart)
        assertEquals(Instant.parse("1959-02-01T00:00:00Z").toEpochMilli(), january.periodEnd)
        assertEquals(316.61, january.value, 0.0001)
    }

    @Test
    fun `missing seasonal adjustment keeps the value and leaves anomaly null`() {
        val points = source.parsePoints(FIXTURE)
        val january = points.first { it.periodStart == Instant.parse("1959-01-01T00:00:00Z").toEpochMilli() }
        val nonNumeric = points.first { it.periodStart == Instant.parse("1960-03-01T00:00:00Z").toEpochMilli() }

        assertEquals(316.61, january.value, 0.0001)
        assertNull(january.anomaly)
        assertEquals(1.0, nonNumeric.value, 0.0001)
        assertNull(nonNumeric.anomaly)
    }

    @Test
    fun `skips malformed rows instead of throwing or zero filling`() {
        val points = source.parsePoints(FIXTURE)

        assertEquals(4, points.size)
        assertTrue(points.none { it.value == 0.0 })
        assertTrue(points.none { it.periodStart == Instant.parse("1960-01-01T00:00:00Z").toEpochMilli() })
        assertTrue(points.none { it.periodStart == Instant.parse("1960-04-01T00:00:00Z").toEpochMilli() })
    }

    @Test
    fun `latest point is the last month in ascending order`() {
        val series = source.parse(FIXTURE, fetchedAt = 0)!!

        assertEquals(Instant.parse("1960-03-01T00:00:00Z").toEpochMilli(), series.latest!!.periodStart)
        assertEquals(1.0, series.latest!!.value, 0.0001)
    }

    @Test
    fun `describes Mauna Loa, not a global average`() {
        val series = source.parse(FIXTURE, fetchedAt = 1_234)!!

        assertEquals("noaa-gml-co2", series.sourceId)
        assertEquals(ClimateMetrics.CO2.id, series.metric)
        assertEquals("mauna_loa", series.region)
        assertEquals("ppm", series.unit)
        assertEquals("Mauna Loa", series.baseline)
        assertEquals("Carbon dioxide", series.label)
        assertEquals(NoaaCo2ClimateSource.URL, series.sourceUrl)
        assertEquals(1_234, series.fetchedAt)
    }

    @Test
    fun `empty or comment-only payload has no series`() {
        assertNull(source.parse("", fetchedAt = 0))
        assertNull(source.parse("# only comments\n# still nothing\n", fetchedAt = 0))
    }

    private companion object {
        const val FIXTURE =
            """# --------------------------------------------------------------------
# USE OF NOAA GML DATA
#
# Contact: Xin Lan
#
year,month,decimal date,average,deseasonalized,ndays,sdev,unc
1958,3,1958.2027,315.71,314.44,-1,-9.99,-0.99
1958,4,1958.2877,317.45,315.16,-1,-9.99,-0.99
1959,1,1959.0411,316.61,,31,0.50,0.20
not,a,valid,row,here,1,1,1
1960,13,1960.0000,1.00,1.00,1,1,1
1960,two,1960.1000,1.00,1.00,1,1,1
1960,3,1960.2000,1.00,not-a-number,1,1,1
1960,4,1960.3000,not-a-number,1.00,1,1,1
"""
    }
}
