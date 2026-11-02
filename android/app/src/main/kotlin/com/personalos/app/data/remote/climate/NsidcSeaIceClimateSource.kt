package com.personalos.app.data.remote.climate

import com.personalos.app.core.climate.ClimateMetric
import com.personalos.app.core.climate.ClimateMetrics
import com.personalos.app.core.climate.ClimatePoint
import com.personalos.app.core.climate.ClimateSeries
import com.personalos.app.core.climate.ClimateSource
import com.personalos.app.core.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.YearMonth
import java.time.ZoneOffset

/**
 * NSIDC Sea Ice Index v4 monthly extent (public domain).
 *
 * One source owns both hemispheres because they share a cadence, a licence and
 * a baseline. The monthly file is per calendar month, so the current UTC month
 * selects the URL (`N_<MM>_extent_v4.0.csv` / `S_<MM>...`). Each row is one
 * year for that month; the anomaly is the extent minus the 1981–2010 mean for
 * the same month, computed from the same parsed file (never a hardcoded
 * baseline).
 */
class NsidcSeaIceClimateSource(
    private val currentMonth: () -> YearMonth = { YearMonth.now(ZoneOffset.UTC) },
    private val now: () -> Long = { System.currentTimeMillis() },
) : ClimateSource {
    override val id: String = SOURCE_ID
    override val metrics: Set<String> = setOf(ClimateMetrics.ARCTIC_ICE.id, ClimateMetrics.ANTARCTIC_ICE.id)
    override val cadenceMinutes: Long = MONTHLY_MINUTES
    override val licence: String = "Public domain (NSIDC Sea Ice Index v4)"

    override suspend fun fetch(): List<ClimateSeries> =
        withContext(Dispatchers.IO) {
            val fetchedAt = now()
            val month = currentMonth()
            urls(month).mapNotNull { (metric, url) ->
                // One hemisphere failing must not erase the other one's card.
                runCatching { parse(raw = Http.getText(url, accept = "text/csv"), metric = metric, sourceUrl = url, fetchedAt = fetchedAt) }
                    .getOrNull()
            }
        }

    /** The two current-month URLs; separated so the month mapping is testable. */
    internal fun urls(month: YearMonth): List<Pair<ClimateMetric, String>> {
        val monthNumber = month.monthValue.toString().padStart(2, '0')
        return listOf(
            ClimateMetrics.ARCTIC_ICE to
                "https://noaadata.apps.nsidc.org/NOAA/G02135/north/monthly/data/N_${monthNumber}_extent_v4.0.csv",
            ClimateMetrics.ANTARCTIC_ICE to
                "https://noaadata.apps.nsidc.org/NOAA/G02135/south/monthly/data/S_${monthNumber}_extent_v4.0.csv",
        )
    }

    /** Pure parser; returns null when the payload has no usable data rows. */
    internal fun parse(
        raw: String,
        metric: ClimateMetric,
        sourceUrl: String,
        fetchedAt: Long,
    ): ClimateSeries? {
        val rows = parseRows(raw)
        if (rows.isEmpty()) return null

        val baselineByMonth =
            rows
                .filter { it.year in BASELINE_YEARS }
                .groupBy { it.month }
                .mapValues { (_, monthRows) -> monthRows.map { it.extent }.average() }

        val points =
            rows
                .mapNotNull { row ->
                    val yearMonth =
                        runCatching { YearMonth.of(row.year, row.month) }.getOrNull()
                            ?: return@mapNotNull null
                    ClimatePoint(
                        periodStart = yearMonth.startUtc(),
                        periodEnd = yearMonth.endUtc(),
                        value = row.extent,
                        anomaly = baselineByMonth[row.month]?.let { row.extent - it },
                    )
                }.sortedBy { it.periodStart }

        if (points.isEmpty()) return null

        return ClimateSeries(
            metric = metric.id,
            label = metric.label,
            region = metric.region,
            unit = metric.unit,
            baseline = metric.baseline,
            points = points,
            sourceId = id,
            sourceUrl = sourceUrl,
            fetchedAt = fetchedAt,
        )
    }

    private fun parseRows(raw: String): List<IceRow> {
        var inData = false
        val rows = mutableListOf<IceRow>()

        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            val columns = trimmed.split(',').map { it.trim() }
            if (!inData) {
                if (columns.firstOrNull()?.equals("year", ignoreCase = true) == true) inData = true
                continue
            }

            if (columns.size < 5) continue

            val year = columns[0].toIntOrNull() ?: continue
            val month = columns[1].toIntOrNull() ?: continue
            if (month !in 1..12) continue

            val extent = columns[4].toDoubleOrNull() ?: continue
            if (!extent.isFinite()) continue

            rows += IceRow(year, month, extent)
        }

        return rows
    }

    private data class IceRow(
        val year: Int,
        val month: Int,
        val extent: Double,
    )

    companion object {
        const val SOURCE_ID = "nsidc-sea-ice"

        private val BASELINE_YEARS = 1981..2010
        private const val MONTHLY_MINUTES = 30L * 24 * 60
    }
}

private fun YearMonth.startUtc(): Long = atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun YearMonth.endUtc(): Long =
    plusMonths(1)
        .atDay(1)
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant()
        .toEpochMilli()
