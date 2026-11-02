package com.personalos.app.core.hazard

/** USGS public feed windows. Historical custom ranges need the FDSN query API. */
enum class HazardWindow(
    val label: String,
    val feed: String,
) {
    DAY("24H", "all_day"),
    WEEK("7D", "all_week"),
    MONTH("30D", "all_month"),
}
