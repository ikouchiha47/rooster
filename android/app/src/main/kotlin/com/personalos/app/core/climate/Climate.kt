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
)

object ClimateMetrics {
    val GLOBAL_TEMP =
        ClimateMetric(
            id = "global_temp_anomaly",
            label = "Global temperature anomaly",
            region = "global",
            unit = "°C",
            baseline = "1951–1980",
        )
    val GLOBAL_TEMP_NCEI =
        ClimateMetric(
            id = "global_temp_anomaly_ncei",
            label = "Global temperature anomaly (NCEI)",
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
        )
    val SOI = ClimateMetric("soi", "Southern Oscillation Index", "global", "index", "1935–present")
    val CO2 = ClimateMetric("co2_mauna_loa", "CO₂ · Mauna Loa", "mauna_loa", "ppm", "dry air")
    val ARCTIC_ICE = ClimateMetric("sea_ice_arctic", "Arctic sea ice", "arctic", "million km²", "1981–2010")
    val ANTARCTIC_ICE = ClimateMetric("sea_ice_antarctic", "Antarctic sea ice", "antarctic", "million km²", "1981–2010")
    val SEA_LEVEL = ClimateMetric("sea_level", "Global mean sea level", "global", "mm", "TOPEX/Jason reference")

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
