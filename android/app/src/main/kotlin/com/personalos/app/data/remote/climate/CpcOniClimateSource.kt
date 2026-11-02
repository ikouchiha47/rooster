package com.personalos.app.data.remote.climate

import com.personalos.app.core.climate.ClimateMetrics
import com.personalos.app.core.climate.ClimatePoint
import com.personalos.app.core.climate.ClimateSeries
import com.personalos.app.core.climate.ClimateSource
import com.personalos.app.core.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * NOAA CPC Oceanic Niño Index (ONI).
 *
 * The file is one row per overlapping 3-month season:
 *
 * ```
 *  SEAS  YR   TOTAL   ANOM
 *   DJF 1950  25.01  -1.32
 * ```
 *
 * `ANOM` is the anomaly against the 1991–2020 baseline, so it is both the
 * series value and its anomaly. The file's `YR` is the year of the season's
 * middle month: `DJF 1950` starts in December 1949 and `NDJ 2023` ends in
 * January 2024.
 */
class CpcOniClimateSource(
    private val now: () -> Long = System::currentTimeMillis,
) : ClimateSource {
    override val id: String = SOURCE_ID
    override val metrics: Set<String> = setOf(ClimateMetrics.ONI.id)

    /** Monthly data; one check per day is enough to pick up a new season. */
    override val cadenceMinutes: Long = 24L * 60
    override val licence: String = "Public domain (NOAA CPC)"

    override suspend fun fetch(): List<ClimateSeries> {
        val raw = withContext(Dispatchers.IO) { Http.getText(URL, accept = "text/plain") }
        return parse(raw, now())
    }

    /**
     * Pure parser, so the season arithmetic is testable without the network.
     * Malformed rows are skipped; a blank payload yields no series.
     */
    internal fun parse(
        raw: String,
        fetchedAt: Long,
    ): List<ClimateSeries> {
        val points =
            raw
                .lineSequence()
                .mapNotNull { parseRow(it) }
                .sortedBy { it.periodStart }
                .toList()

        if (points.isEmpty()) return emptyList()

        return listOf(
            ClimateSeries(
                metric = ClimateMetrics.ONI.id,
                label = ClimateMetrics.ONI.label,
                region = ClimateMetrics.ONI.region,
                unit = ClimateMetrics.ONI.unit,
                baseline = ClimateMetrics.ONI.baseline,
                points = points,
                sourceId = id,
                sourceUrl = URL,
                fetchedAt = fetchedAt,
            ),
        )
    }

    private fun parseRow(line: String): ClimatePoint? {
        val columns = line.trim().split(WHITESPACE)
        if (columns.size < 4) return null

        val season = SEASONS[columns[0]] ?: return null
        val year = columns[1].toIntOrNull() ?: return null
        if (year !in MIN_YEAR..MAX_YEAR) return null
        val anomaly = columns[3].toDoubleOrNull() ?: return null
        if (!anomaly.isFinite()) return null

        val start = LocalDate.of(year + season.startYearOffset, season.startMonth, 1)
        val end = LocalDate.of(year + season.endYearOffset, season.endMonth, 1).plusMonths(1)

        return ClimatePoint(
            periodStart = start.utcMillis(),
            periodEnd = end.utcMillis(),
            value = anomaly,
            anomaly = anomaly,
        )
    }

    private fun LocalDate.utcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** A 3-month season; offsets apply to the file's `YR` (the middle month). */
    private data class Season(
        val startMonth: Int,
        val endMonth: Int,
        val startYearOffset: Int = 0,
        val endYearOffset: Int = 0,
    )

    companion object {
        const val SOURCE_ID = "noaa-cpc-oni"
        const val URL = "https://www.cpc.ncep.noaa.gov/data/indices/oni.ascii.txt"

        private val WHITESPACE = Regex("\\s+")

        private const val MIN_YEAR = 1000
        private const val MAX_YEAR = 9999

        /**
         * The CPC season vocabulary. `DJF` starts in the previous December and
         * `NDJ` ends in the next January; every other label is inside one year.
         */
        private val SEASONS: Map<String, Season> =
            mapOf(
                "DJF" to Season(startMonth = 12, endMonth = 2, startYearOffset = -1),
                "JFM" to Season(startMonth = 1, endMonth = 3),
                "FMA" to Season(startMonth = 2, endMonth = 4),
                "MAM" to Season(startMonth = 3, endMonth = 5),
                "AMJ" to Season(startMonth = 4, endMonth = 6),
                "MJJ" to Season(startMonth = 5, endMonth = 7),
                "JJA" to Season(startMonth = 6, endMonth = 8),
                "JAS" to Season(startMonth = 7, endMonth = 9),
                "ASO" to Season(startMonth = 8, endMonth = 10),
                "SON" to Season(startMonth = 9, endMonth = 11),
                "OND" to Season(startMonth = 10, endMonth = 12),
                "NDJ" to Season(startMonth = 11, endMonth = 1, endYearOffset = 1),
            )
    }
}
