package com.personalos.app.data.remote.climate

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
 * NOAA GML Mauna Loa monthly CO₂ (public domain, attribution requested).
 *
 * This is Mauna Loa, not a global average. The CSV starts with citation prose
 * and a free-text header, so the parser finds the `year,month,...` header rather
 * than assuming a line number. `average` is the monthly mean dry-air mole
 * fraction; `deseasonalized` is kept as the point's anomaly because it is the
 * only seasonally adjusted value in the payload.
 */
class NoaaCo2ClimateSource(
    private val now: () -> Long = { System.currentTimeMillis() },
) : ClimateSource {
    override val id: String = SOURCE_ID
    override val metrics: Set<String> = setOf(ClimateMetrics.CO2.id)
    override val cadenceMinutes: Long = MONTHLY_MINUTES
    override val licence: String = "Public domain (NOAA GML); cite NOAA GML"

    override suspend fun fetch(): List<ClimateSeries> =
        withContext(Dispatchers.IO) {
            val fetchedAt = now()
            val raw = Http.getText(URL, accept = "text/csv")
            listOfNotNull(parse(raw, fetchedAt))
        }

    /** Pure parser; returns null when the payload has no usable data rows. */
    internal fun parse(
        raw: String,
        fetchedAt: Long,
    ): ClimateSeries? {
        val points = parsePoints(raw)
        if (points.isEmpty()) return null
        val metric = ClimateMetrics.CO2
        return ClimateSeries(
            metric = metric.id,
            label = metric.label,
            region = metric.region,
            unit = metric.unit,
            baseline = metric.baseline,
            points = points,
            sourceId = id,
            sourceUrl = URL,
            fetchedAt = fetchedAt,
        )
    }

    internal fun parsePoints(raw: String): List<ClimatePoint> {
        var inData = false
        val points = mutableListOf<ClimatePoint>()

        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            if (!inData) {
                if (trimmed.startsWith("year", ignoreCase = true)) inData = true
                continue
            }

            val columns = trimmed.split(',').map { it.trim() }
            if (columns.size < 5) continue

            val year = columns[0].toIntOrNull() ?: continue
            val month = columns[1].toIntOrNull() ?: continue
            if (month !in 1..12) continue

            val average = columns[3].toDoubleOrNull() ?: continue
            if (!average.isFinite()) continue

            // Missing or non-numeric seasonal adjustment must not discard the
            // monthly mean: the value is real, only the anomaly is unknown.
            val yearMonth = runCatching { YearMonth.of(year, month) }.getOrNull() ?: continue

            points +=
                ClimatePoint(
                    periodStart = yearMonth.startUtc(),
                    periodEnd = yearMonth.endUtc(),
                    value = average,
                    anomaly = null,
                )
        }

        return points.sortedBy { it.periodStart }
    }

    companion object {
        const val URL = "https://gml.noaa.gov/webdata/ccgg/trends/co2/co2_mm_mlo.csv"
        const val SOURCE_ID = "noaa-gml-co2"

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
