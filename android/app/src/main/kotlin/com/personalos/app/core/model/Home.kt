package com.personalos.app.core.model

/**
 * Home-screen domain models. Deliberately free of Compose/Android types so the
 * data source behind them can be swapped (sample data, HTTP, Room) without
 * touching the UI.
 */

enum class Accent { VERMILION, INDIGO, MUSTARD, TEAL, PERIWINKLE, PLUM, CYAN, RUST, CHARTREUSE, INK }

data class Money(
    val amount: Double,
    val currency: String = "INR",
    val note: String? = null,
)

/** A tracked exchange rate, e.g. USD/INR. Stands in for fares until those are sourced. */
data class FxRate(
    val id: String,
    val pair: String,
    val rate: Double,
    val note: String? = null,
)

data class WeatherSnapshot(
    val place: String,
    val temperatureC: Int,
    val summary: String,
    val detail: String,
    /**
     * The coordinates the reading came from, when known.
     *
     * Carried so the present location - which is deliberately not reverse
     * geocoded - can identify itself honestly by position rather than borrowing
     * a configured place name.
     */
    val lat: Double? = null,
    val lon: Double? = null,
)

/**
 * One day of a cached forecast week.
 *
 * Open-Meteo already returns seven days per fetch - the snapshot only ever read
 * day 0, so the rest of the week was being fetched, cached, and thrown away.
 */
data class WeatherDay(
    /** ISO date exactly as Open-Meteo returns it, e.g. `2026-09-14`. */
    val date: String,
    val maxC: Int,
    val minC: Int,
    val rainChance: Int,
    /** WMO weather code when the source carried one; null keeps the rain-chance bands. */
    val weatherCode: Int? = null,
)

data class NowPlaying(
    val station: String,
    val place: String,
    val bitrateKbps: Int,
    val live: Boolean = true,
)

data class PinnedItem(
    val id: String,
    val label: String,
    val title: String,
    val meta: String,
    val accent: Accent,
)

/** The Home "At a glance" figures. Money is sample data until parsing lands. */
data class Glance(
    val moneyOutToday: Money?,
    val alertsFired24h: Int,
    val events24h: Int,
    val totalEvents: Int,
)
