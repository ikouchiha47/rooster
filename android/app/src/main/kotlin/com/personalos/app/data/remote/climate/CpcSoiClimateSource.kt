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
 * NOAA CPC Southern Oscillation Index (SOI).
 *
 * The file is a fixed-width monthly table with three narrative header lines:
 *
 * ```
 * YEAR   JAN   FEB   MAR   APR   MAY   JUN   JUL   AUG   SEP   OCT   NOV   DEC
 * 1951   2.5   1.5  -0.2  -0.5  -1.1   0.3  -1.7  -0.4  -1.8  -1.6  -1.3  -1.2
 * ```
 *
 * Future months are filled with the `-999.9` missing-value sentinel; those
 * months are absent from the series rather than treated as observations. The
 * value is the standardised SOI, so no anomaly is derived.
 */
class CpcSoiClimateSource(
    private val now: () -> Long = System::currentTimeMillis,
) : ClimateSource {
    override val id: String = SOURCE_ID
    override val metrics: Set<String> = setOf(ClimateMetrics.SOI.id)

    /** Monthly data; one check per day is enough to pick up a new month. */
    override val cadenceMinutes: Long = 24L * 60
    override val licence: String = "Public domain (NOAA CPC)"

    override suspend fun fetch(): List<ClimateSeries> {
        val raw = withContext(Dispatchers.IO) { Http.getText(URL, accept = "text/plain") }
        return parse(raw, now())
    }

    /**
     * Pure parser, so the fixed-width columns are testable without the network.
     * Header and malformed rows are skipped; a blank payload yields no series.
     */
    internal fun parse(
        raw: String,
        fetchedAt: Long,
    ): List<ClimateSeries> {
        val points =
            raw
                .lineSequence()
                .flatMap { parseRow(it) }
                .sortedBy { it.periodStart }
                .toList()

        if (points.isEmpty()) return emptyList()

        return listOf(
            ClimateSeries(
                metric = ClimateMetrics.SOI.id,
                label = ClimateMetrics.SOI.label,
                region = ClimateMetrics.SOI.region,
                unit = ClimateMetrics.SOI.unit,
                baseline = ClimateMetrics.SOI.baseline,
                points = points,
                sourceId = id,
                sourceUrl = URL,
                fetchedAt = fetchedAt,
            ),
        )
    }

    /** One table row: a year and up to twelve monthly values. */
    private fun parseRow(line: String): List<ClimatePoint> {
        val row = line.trim()
        if (row.isEmpty()) return emptyList()

        val year = row.take(YEAR_WIDTH).toIntOrNull() ?: return emptyList()
        if (year !in MIN_YEAR..MAX_YEAR) return emptyList()

        val values =
            if (row.length >= YEAR_WIDTH + MONTHS * MONTH_WIDTH) {
                // Fixed width: six characters per month, whether or not the
                // fields happen to touch (e.g. `-2.0-999.9-999.9`).
                (0 until MONTHS).map { index ->
                    val start = YEAR_WIDTH + index * MONTH_WIDTH
                    row.substring(start, start + MONTH_WIDTH).trim().toDoubleOrNull()
                }
            } else {
                // Tolerate whitespace-delimited copies of the same table.
                val columns = row.split(WHITESPACE)
                if (columns.size < MONTHS + 1) return emptyList()
                (0 until MONTHS).map { index -> columns[index + 1].toDoubleOrNull() }
            }

        return values.mapIndexedNotNull { index, value ->
            if (value == null || !value.isFinite() || value <= SENTINEL) return@mapIndexedNotNull null

            val start = LocalDate.of(year, index + 1, 1)
            ClimatePoint(
                periodStart = start.utcMillis(),
                periodEnd = start.plusMonths(1).utcMillis(),
                value = value,
            )
        }
    }

    private fun LocalDate.utcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    companion object {
        const val SOURCE_ID = "noaa-cpc-soi"
        const val URL = "https://www.cpc.ncep.noaa.gov/data/indices/soi"

        private const val YEAR_WIDTH = 4
        private const val MONTH_WIDTH = 6
        private const val MONTHS = 12
        private const val MIN_YEAR = 1000
        private const val MAX_YEAR = 9999

        /** CPC's missing-value marker, absent rather than zero. */
        private const val SENTINEL = -999.0

        private val WHITESPACE = Regex("\\s+")
    }
}
