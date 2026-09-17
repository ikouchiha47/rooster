package com.personalos.app.ui.weather

import com.personalos.app.core.model.WeatherDay
import com.personalos.app.core.model.WeatherSnapshot
import com.personalos.app.ui.common.Glyph

/**
 * How the sky reads, and the drawn glyph that stands in for it.
 *
 * The stored WMO `weather_code` decides first — 95+ genuinely is a
 * thunderstorm, which no rain-chance band can know. Days without a code fall
 * back to the rain-probability bands, whose thresholds stay wide on purpose: a
 * number that jitters by a few percent should not make the icon flicker.
 */
internal enum class WeatherCondition(
    val glyph: Glyph,
    val label: String,
) {
    Clear(Glyph.Sun, "Clear"),
    PartlyCloudy(Glyph.CloudSun, "Partly cloudy"),
    Cloudy(Glyph.Cloud, "Cloudy"),
    Fog(Glyph.Cloud, "Fog"),
    Drizzle(Glyph.CloudRain, "Drizzle"),
    Rain(Glyph.CloudRain, "Rain"),
    Showers(Glyph.CloudRain, "Showers"),
    Snow(Glyph.Cloud, "Snow"),
    Storm(Glyph.Storm, "Thunderstorm"),
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

/** Read a WMO weather code: thunderstorm, snow, rain and fog are real states, not guesses. */
internal fun weatherCodeCondition(code: Int): WeatherCondition =
    when (code) {
        in 95..99 -> WeatherCondition.Storm
        in 71..77, in 85..86 -> WeatherCondition.Snow
        in 61..67 -> WeatherCondition.Rain
        in 80..82 -> WeatherCondition.Showers
        in 51..57 -> WeatherCondition.Drizzle
        45, 48 -> WeatherCondition.Fog
        3 -> WeatherCondition.Cloudy
        2 -> WeatherCondition.PartlyCloudy
        else -> WeatherCondition.Clear
    }

internal fun WeatherDay.condition(): WeatherCondition = weatherCode?.let(::weatherCodeCondition) ?: weatherCondition(rainChance)

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
