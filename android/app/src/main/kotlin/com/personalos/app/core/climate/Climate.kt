package com.personalos.app.core.climate

/** One point in a climate series: a month, week or season. */
data class ClimatePoint(
    val periodStart: Long,
    val periodEnd: Long,
    val value: Double,
    val anomaly: Double? = null,
)

/** A named series with its source, unit and baseline. */
data class ClimateSeries(
    val metric: String,
    val label: String,
    val region: String,
    val unit: String,
    val baseline: String,
    val points: List<ClimatePoint>,
    val sourceId: String,
    val sourceUrl: String,
    val fetchedAt: Long,
) {
    val latest: ClimatePoint? get() = points.lastOrNull()
    val previous: ClimatePoint? get() = points.dropLast(1).lastOrNull()
}

/** A metric definition; labels and baselines are data, not UI guesses. */
data class ClimateMetric(
    val id: String,
    val label: String,
    val region: String,
    val unit: String,
    val baseline: String,
    /** Plain-language explanation; acronyms belong in the secondary line. */
    val description: String = "",
)

object ClimateMetrics {
    val GLOBAL_TEMP =
        ClimateMetric(
            id = "global_temp_anomaly",
            label = "Global temperature",
            region = "global",
            unit = "°C",
            baseline = "1951–1980",
            description = "How much warmer than the 1951–1980 average.",
        )
    val GLOBAL_TEMP_NCEI =
        ClimateMetric(
            id = "global_temp_anomaly_ncei",
            label = "Global temperature (NCEI)",
            region = "global",
            unit = "°C",
            baseline = "1901–2000",
        )
    val ONI =
        ClimateMetric(
            id = "oni",
            label = "ENSO ONI",
            region = "nino34",
            unit = "°C",
            baseline = "1991–2020",
            description = "Pacific Ocean pattern that shifts rainfall and heat.",
        )
    val SOI = ClimateMetric("soi", "Southern Oscillation Index", "global", "index", "1935–present")
    val CO2 =
        ClimateMetric(
            id = "co2_mauna_loa",
            label = "Carbon dioxide",
            region = "mauna_loa",
            unit = "ppm",
            baseline = "Mauna Loa",
            description = "Heat-trapping gas measured at Mauna Loa, Hawaii.",
        )
    val ARCTIC_ICE =
        ClimateMetric(
            id = "sea_ice_arctic",
            label = "Arctic sea ice",
            region = "arctic",
            unit = "million km²",
            baseline = "1981–2010",
            description = "This month compared with the 1981–2010 average.",
        )
    val ANTARCTIC_ICE =
        ClimateMetric(
            id = "sea_ice_antarctic",
            label = "Antarctic sea ice",
            region = "antarctic",
            unit = "million km²",
            baseline = "1981–2010",
            description = "This month compared with the 1981–2010 average.",
        )
    val SEA_LEVEL =
        ClimateMetric(
            id = "sea_level",
            label = "Sea level",
            region = "global",
            unit = "mm",
            baseline = "since 1993",
            description = "Satellite-measured global sea-level rise.",
        )

    val ALL: List<ClimateMetric> =
        listOf(GLOBAL_TEMP, GLOBAL_TEMP_NCEI, ONI, SOI, CO2, ARCTIC_ICE, ANTARCTIC_ICE, SEA_LEVEL)

    fun byId(id: String): ClimateMetric? = ALL.firstOrNull { it.id == id }
}

/**
 * One source of climate series. Adapters fetch raw data and normalise it; the
 * repository owns caching, staleness and the UI model.
 */
interface ClimateSource {
    val id: String
    val metrics: Set<String>
    val cadenceMinutes: Long
    val licence: String

    suspend fun fetch(): List<ClimateSeries>
}

/** The UI model: every known series, newest fetch time, and a stale marker. */
data class ClimateDashboard(
    val series: Map<String, ClimateSeries>,
    val fetchedAt: Long?,
    val stale: Boolean,
)

/** Plain-language ENSO state; ONI thresholds are the technical detail. */
fun ensoState(oni: Double): String =
    when {
        oni >= 2.0 -> "Very strong El Niño"
        oni >= 1.5 -> "Strong El Niño"
        oni >= 0.5 -> "El Niño"
        oni <= -2.0 -> "Very strong La Niña"
        oni <= -1.5 -> "Strong La Niña"
        oni <= -0.5 -> "La Niña"
        else -> "Neutral"
    }
