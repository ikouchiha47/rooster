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
import kotlin.math.floor

/**
 * NOAA STAR / NASA altimetry global mean sea level (public use, attribution
 * requested).
 *
 * The payload is a comment block followed by a `year,TOPEX/Poseidon,Jason-1,...`
 * header and roughly 10-day records. A metric needs a monthly period, so rows
 * are grouped by the calendar month of the decimal date and reduced to the mean
 * of their (per-row) altimetry values. Values are already anomalies against the
 * TOPEX/Jason reference in millimetres; the source's annual signal is retained,
 * so no second anomaly is invented.
 */
class NoaaSeaLevelClimateSource(
    private val now: () -> Long = { System.currentTimeMillis() },
) : ClimateSource {
    override val id: String = SOURCE_ID
    override val metrics: Set<String> = setOf(ClimateMetrics.SEA_LEVEL.id)
    override val cadenceMinutes: Long = MONTHLY_MINUTES
    override val licence: String = "Public use (NOAA Laboratory for Satellite Altimetry); cite NOAA LSA"

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
        val metric = ClimateMetrics.SEA_LEVEL
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
        val months = linkedMapOf<YearMonth, MutableList<Double>>()

        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            val columns = trimmed.split(',').map { it.trim() }
            if (!inData) {
                if (columns.firstOrNull()?.equals("year", ignoreCase = true) == true) inData = true
                continue
            }

            if (columns.size < 2) continue

            val decimalYear = columns[0].toDoubleOrNull() ?: continue
            if (!decimalYear.isFinite() || decimalYear < 0) continue

            val values = columns.drop(1).mapNotNull { it.toDoubleOrNull()?.takeIf { value -> value.isFinite() } }
            if (values.isEmpty()) continue

            val yearMonth = monthOf(decimalYear) ?: continue

            // Some rows carry overlapping missions in two columns. The row mean
            // keeps the date's observation from being counted twice.
            months.getOrPut(yearMonth) { mutableListOf() } += values.average()
        }

        return months
            .map { (yearMonth, values) ->
                ClimatePoint(
                    periodStart = yearMonth.startUtc(),
                    periodEnd = yearMonth.endUtc(),
                    value = values.average(),
                    anomaly = null,
                )
            }.sortedBy { it.periodStart }
    }

    /** Decimal year -> UTC calendar month (the payload has no month column). */
    private fun monthOf(decimalYear: Double): YearMonth? {
        val year = floor(decimalYear).toInt()
        val fraction = decimalYear - year
        val month = (fraction * 12.0).toInt() + 1
        return runCatching { YearMonth.of(year, month.coerceIn(1, 12)) }.getOrNull()
    }

    companion object {
        const val URL =
            "https://www.star.nesdis.noaa.gov/socd/lsa/SeaLevelRise/slr/slr_sla_gbl_keep_all_66.csv"
        const val SOURCE_ID = "noaa-star-sea-level"

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
