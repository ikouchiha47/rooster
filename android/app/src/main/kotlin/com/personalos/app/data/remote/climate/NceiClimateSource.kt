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
 * NOAA NCEI Climate at a Glance global land-ocean temperature departures.
 *
 * The CSV carries `#` metadata lines, then a `Date,Departure from Average`
 * header; dates are `YYYYMM` and departures are vs 1901–2000. It is a
 * cross-check for GISTEMP with a different baseline, never averaged with it.
 */
class NceiClimateSource(
    private val now: () -> Long = System::currentTimeMillis,
) : ClimateSource {
    override val id: String = ID
    override val metrics: Set<String> = setOf(ClimateMetrics.GLOBAL_TEMP_NCEI.id)
    override val cadenceMinutes: Long = CADENCE_MINUTES
    override val licence: String = "NOAA NCEI Climate at a Glance — public domain (NOAA)"

    override suspend fun fetch(): List<ClimateSeries> =
        withContext(Dispatchers.IO) {
            val csv = Http.getText(URL, accept = "text/csv")
            listOf(parseNceiCsv(csv, now()))
        }

    companion object {
        const val ID = "noaa-ncei"
        const val URL =
            "https://www.ncei.noaa.gov/access/monitoring/climate-at-a-glance/global/time-series/globe/land_ocean/1/0/1880-2026.csv"

        /** Monthly series; refreshed at most once a day. */
        const val CADENCE_MINUTES = 24L * 60L
    }
}

/**
 * Pure CSV parser: `#` comments are ignored, the `Date` header anchors the
 * columns, and `YYYYMM` rows become UTC month points ordered oldest first.
 */
internal fun parseNceiCsv(
    csv: String,
    fetchedAt: Long,
): ClimateSeries {
    val lines =
        csv
            .lineSequence()
            .map { it.trim().removePrefix("\uFEFF") }
            .filter { it.isNotEmpty() }
            .toList()

    val headerIndex =
        lines.indexOfFirst {
            it
                .substringBefore(',')
                .trim()
                .trim('"')
                .equals("Date", ignoreCase = true)
        }

    val points =
        if (headerIndex < 0) {
            emptyList()
        } else {
            val header = lines[headerIndex].split(',').map { it.trim().trim('"') }
            val dateColumn = header.indexOfFirst { it.equals("Date", ignoreCase = true) }
            val valueColumn = header.indexOfFirst { it.contains("Departure", ignoreCase = true) }

            lines
                .drop(headerIndex + 1)
                .mapNotNull { line -> parseNceiRow(line, dateColumn, valueColumn) }
                .sortedBy { it.periodStart }
        }

    val metric = ClimateMetrics.GLOBAL_TEMP_NCEI
    return ClimateSeries(
        metric = metric.id,
        label = metric.label,
        region = metric.region,
        unit = metric.unit,
        baseline = metric.baseline,
        points = points,
        sourceId = NceiClimateSource.ID,
        sourceUrl = NceiClimateSource.URL,
        fetchedAt = fetchedAt,
    )
}

private fun parseNceiRow(
    line: String,
    dateColumn: Int,
    valueColumn: Int,
): ClimatePoint? {
    val cells = line.split(',').map { it.trim().trim('"') }
    val date = cells.getOrNull(dateColumn) ?: return null
    if (date.length != 6 || date.any { !it.isDigit() }) return null

    val year = date.substring(0, 4).toIntOrNull() ?: return null
    val month = date.substring(4, 6).toIntOrNull() ?: return null
    if (month !in 1..12) return null

    val anomaly = cells.getOrNull(valueColumn)?.toDoubleOrNull() ?: return null
    val start = utcMonthStart(year, month)
    val end = utcMonthStart(year, month, plusMonths = 1)
    return ClimatePoint(
        periodStart = start,
        periodEnd = end,
        value = anomaly,
        anomaly = anomaly,
    )
}

private fun utcMonthStart(
    year: Int,
    month: Int,
    plusMonths: Long = 0,
): Long =
    LocalDate
        .of(year, month, 1)
        .plusMonths(plusMonths)
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant()
        .toEpochMilli()
