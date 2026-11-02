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
 * NASA GISTEMP v4 global land-ocean temperature anomalies, monthly.
 *
 * The CSV starts with a title line followed by a `Year,Jan,...,Dec,...` header;
 * months are anomalies against 1951–1980. Missing months are `***` and are
 * skipped rather than stored as zero.
 */
class GistempClimateSource(
    private val now: () -> Long = System::currentTimeMillis,
) : ClimateSource {
    override val id: String = ID
    override val metrics: Set<String> = setOf(ClimateMetrics.GLOBAL_TEMP.id)
    override val cadenceMinutes: Long = CADENCE_MINUTES
    override val licence: String = "NASA GISTEMP v4 — public domain (NASA)"

    override suspend fun fetch(): List<ClimateSeries> =
        withContext(Dispatchers.IO) {
            val csv = Http.getText(URL, accept = "text/csv")
            listOf(parseGistempCsv(csv, now()))
        }

    companion object {
        const val ID = "nasa-gistemp"
        const val URL = "https://data.giss.nasa.gov/gistemp/tabledata_v4/GLB.Ts+dSST.csv"

        /** Monthly series; refreshed at most once a day. */
        const val CADENCE_MINUTES = 24L * 60L
    }
}

/**
 * Pure CSV parser: title/comment lines are ignored, the first `Year` header row
 * anchors the columns, and points come back ordered by month.
 */
internal fun parseGistempCsv(
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
            it.substringBefore(',').trim().equals("Year", ignoreCase = true)
        }

    val points =
        if (headerIndex < 0) {
            emptyList()
        } else {
            val header = lines[headerIndex].split(',').map { it.trim() }
            val yearColumn = header.indexOfFirst { it.equals("Year", ignoreCase = true) }
            val monthColumns =
                listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
                    .map { month -> header.indexOfFirst { it.equals(month, ignoreCase = true) } }

            lines
                .drop(headerIndex + 1)
                .flatMap { line -> parseGistempRow(line, yearColumn, monthColumns) }
                .sortedBy { it.periodStart }
        }

    val metric = ClimateMetrics.GLOBAL_TEMP
    return ClimateSeries(
        metric = metric.id,
        label = metric.label,
        region = metric.region,
        unit = metric.unit,
        baseline = metric.baseline,
        points = points,
        sourceId = GistempClimateSource.ID,
        sourceUrl = GistempClimateSource.URL,
        fetchedAt = fetchedAt,
    )
}

private fun parseGistempRow(
    line: String,
    yearColumn: Int,
    monthColumns: List<Int>,
): List<ClimatePoint> {
    val cells = line.split(',').map { it.trim() }
    val year = cells.getOrNull(yearColumn)?.toIntOrNull() ?: return emptyList()

    return monthColumns.mapIndexedNotNull { index, column ->
        val anomaly = cells.getOrNull(column)?.toDoubleOrNull() ?: return@mapIndexedNotNull null
        val month = index + 1
        val start = utcMonthStart(year, month)
        val end = utcMonthStart(year, month, plusMonths = 1)
        ClimatePoint(
            periodStart = start,
            periodEnd = end,
            value = anomaly,
            anomaly = anomaly,
        )
    }
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
