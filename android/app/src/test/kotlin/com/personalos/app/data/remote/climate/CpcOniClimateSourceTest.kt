package com.personalos.app.data.remote.climate

import com.personalos.app.core.climate.ClimateMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * ONI rows are 3-month seasons, not calendar months. The file's year is the
 * middle month, so `DJF 1950` starts in December 1949 and `NDJ 2023` ends in
 * January 2024; those boundaries are the ones most likely to drift.
 */
class CpcOniClimateSourceTest {
    private val source = CpcOniClimateSource(now = { FIXED_NOW })

    private fun series(raw: String) = source.parse(raw, FIXED_NOW).single()

    @Test
    fun `maps every season label to the correct month boundaries`() {
        val points = series(ALL_SEASONS).points

        // start (year, month) to end-exclusive (year, month)
        val expected =
            listOf(
                (2022 to 12) to (2023 to 3),
                (2023 to 1) to (2023 to 4),
                (2023 to 2) to (2023 to 5),
                (2023 to 3) to (2023 to 6),
                (2023 to 4) to (2023 to 7),
                (2023 to 5) to (2023 to 8),
                (2023 to 6) to (2023 to 9),
                (2023 to 7) to (2023 to 10),
                (2023 to 8) to (2023 to 11),
                (2023 to 9) to (2023 to 12),
                (2023 to 10) to (2024 to 1),
                (2023 to 11) to (2024 to 2),
            )

        assertEquals(12, points.size)
        expected.forEachIndexed { index, (start, end) ->
            assertEquals("start of point $index", utc(start.first, start.second), points[index].periodStart)
            assertEquals("end of point $index", utc(end.first, end.second), points[index].periodEnd)
        }
    }

    @Test
    fun `DJF starts in December of the previous year`() {
        val point = series(" DJF 1950  25.01  -1.32").points.single()

        assertEquals(utc(1949, 12), point.periodStart)
        assertEquals(utc(1950, 3), point.periodEnd)
    }

    @Test
    fun `NDJ ends in January of the next year`() {
        val point = series(" NDJ 2023  28.00   1.50").points.single()

        assertEquals(utc(2023, 11), point.periodStart)
        assertEquals(utc(2024, 2), point.periodEnd)
    }

    @Test
    fun `value and anomaly are both the ANOM column`() {
        val point = series(" MAM 2024  28.09   0.46").points.single()

        assertEquals(0.46, point.value, 0.0001)
        assertEquals(0.46, point.anomaly ?: error("missing anomaly"), 0.0001)
    }

    @Test
    fun `sorts points and exposes the latest season`() {
        val series =
            series(
                """
                 SEAS  YR   TOTAL   ANOM
                  JJA 2024  29.50   1.80
                  DJF 2024  27.10  -0.50
                  MAM 2024  28.09   0.46
                """,
            )

        assertEquals(listOf(utc(2023, 12), utc(2024, 3), utc(2024, 6)), series.points.map { it.periodStart })
        assertEquals(utc(2024, 6), series.latest?.periodStart)
        assertEquals(1.80, series.latest?.value ?: error("missing latest"), 0.0001)
    }

    @Test
    fun `skips malformed rows but keeps valid ones`() {
        val parsed =
            source.parse(
                """
                SEAS  YR   TOTAL   ANOM
                DJF 1950 25.01
                XYZ 1950 25.01 1.00
                DJF nope 25.01 1.00
                DJF 1950 25.01 n/a
                DJF 1950 25.01 NaN
                DJF 9999999999 25.01 1.00

                  DJF 1950  25.01  -1.32
                """,
                FIXED_NOW,
            )

        val point = parsed.single().points.single()
        assertEquals(-1.32, point.value, 0.0001)
        assertEquals(utc(1949, 12), point.periodStart)
    }

    @Test
    fun `blank payload yields no series`() {
        assertTrue(source.parse("", FIXED_NOW).isEmpty())
        assertTrue(source.parse("  \n\n ", FIXED_NOW).isEmpty())
        assertTrue(source.parse(" SEAS  YR   TOTAL   ANOM", FIXED_NOW).isEmpty())
    }

    @Test
    fun `series carries the ONI metric metadata`() {
        val series = series(" DJF 2024  27.10  -0.50")

        assertEquals(ClimateMetrics.ONI.id, series.metric)
        assertEquals(ClimateMetrics.ONI.label, series.label)
        assertEquals("nino34", series.region)
        assertEquals("°C", series.unit)
        assertEquals("1991–2020", series.baseline)
        assertEquals("noaa-cpc-oni", series.sourceId)
        assertEquals(CpcOniClimateSource.URL, series.sourceUrl)
        assertEquals(FIXED_NOW, series.fetchedAt)
    }

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

        val ALL_SEASONS =
            """
             SEAS  YR   TOTAL   ANOM
              DJF 2023  27.00  -0.10
              JFM 2023  27.10  -0.20
              FMA 2023  27.20  -0.30
              MAM 2023  27.30  -0.40
              AMJ 2023  27.40  -0.50
              MJJ 2023  27.50  -0.60
              JJA 2023  27.60  -0.70
              JAS 2023  27.70  -0.80
              ASO 2023  27.80  -0.90
              SON 2023  27.90  -1.00
              OND 2023  28.00  -1.10
              NDJ 2023  28.10  -1.20
            """
    }
}
