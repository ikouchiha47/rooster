package com.personalos.app.ui.weather

import com.personalos.app.core.model.WeatherDay
import com.personalos.app.core.model.WeatherSnapshot
import com.personalos.app.ui.common.Glyph

/**
 * How the sky reads, and the drawn glyph that stands in for it.
 *
 * The provider already carries a rain probability per day, and folds the current
 * day into a one-word summary (`Rain 55%` / `Dry` / `Unavailable`). That is
 * enough to choose an icon honestly - no weather code, no second source, no new
 * dependency. Thresholds are wide bands on purpose: a number that jitters by a
 * few percent should not make the icon flicker.
 */
internal enum class WeatherCondition(
    val glyph: Glyph,
) {
    Clear(Glyph.Sun),
    PartlyCloudy(Glyph.CloudSun),
    Cloudy(Glyph.Cloud),
    Rain(Glyph.CloudRain),
    Storm(Glyph.Storm),
}

/** Band a rain chance from clear (0) to storm (100). */
internal fun weatherCondition(rainChance: Int): WeatherCondition =
    when {
        rainChance >= 90 -> WeatherCondition.Storm
        rainChance >= 70 -> WeatherCondition.Rain
        rainChance >= 45 -> WeatherCondition.Cloudy
        rainChance >= 20 -> WeatherCondition.PartlyCloudy
        else -> WeatherCondition.Clear
    }

internal fun WeatherDay.condition(): WeatherCondition = weatherCondition(rainChance)

/**
 * Read a snapshot's self-summary. `Rain 55%` gives the number; `Dry` reads as
 * clear; `Unavailable` stays a plain cloud rather than pretending to be sunny.
 */
internal fun WeatherSnapshot.condition(): WeatherCondition {
    val pct =
        RAIN_PCT
            .find(summary)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
    if (pct != null) return weatherCondition(pct)
    return when (summary.trim().lowercase()) {
        "unavailable" -> WeatherCondition.Cloudy
        else -> WeatherCondition.Clear
    }
}

private val RAIN_PCT = Regex("(\\d+)")
