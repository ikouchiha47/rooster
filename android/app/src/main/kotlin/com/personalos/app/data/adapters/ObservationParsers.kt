package com.personalos.app.data.adapters

import com.personalos.app.core.net.Http
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure parsers for gauge observations — no Android, no store, unit-tested.
 * The adapters fetch bytes; these turn bytes into numbers. The UI never calls
 * them; tiles read the store.
 */
object WeatherCurrentParser {
    data class Current(
        val tempC: Double,
        val rainMm: Double,
        val humidityPct: Double,
        val windKmh: Double,
        /** WMO weather code, when the reply carries one. */
        val weatherCode: Double?,
    )

    fun parse(raw: String): Current? {
        if (raw.isBlank()) return null
        val root = runCatching { Http.json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val current = root["current"]?.jsonObject ?: return null
        val temp = current["temperature_2m"]?.jsonPrimitive?.doubleOrNull() ?: return null
        return Current(
            tempC = temp,
            rainMm = current["precipitation"]?.jsonPrimitive?.doubleOrNull() ?: 0.0,
            humidityPct = current["relative_humidity_2m"]?.jsonPrimitive?.doubleOrNull() ?: 0.0,
            windKmh = current["wind_speed_10m"]?.jsonPrimitive?.doubleOrNull() ?: 0.0,
            weatherCode = current["weather_code"]?.jsonPrimitive?.doubleOrNull(),
        )
    }

    private fun kotlinx.serialization.json.JsonPrimitive.doubleOrNull(): Double? = runCatching { double }.getOrNull()
}

/**
 * One forecast day exactly as the week UI renders it. Pure — the adapter
 * writes these as keyed observations, the repository reads them back.
 */
data class ForecastDay(
    val date: String,
    val maxC: Double,
    val minC: Double,
    val rainChance: Double,
    val weatherCode: Double?,
)

object WeatherWeekParser {
    fun parse(raw: String): List<ForecastDay> {
        if (raw.isBlank()) return emptyList()
        val root = runCatching { Http.json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return emptyList()
        val daily = root["daily"]?.jsonObject ?: return emptyList()
        val dates = daily["time"]?.jsonArray?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: return emptyList()
        val maxima = daily["temperature_2m_max"]?.jsonArray ?: return emptyList()
        val minima = daily["temperature_2m_min"]?.jsonArray ?: return emptyList()
        val rain = daily["precipitation_probability_max"]?.jsonArray
        val codes = daily["weather_code"]?.jsonArray
        return dates.mapIndexedNotNull { index, date ->
            val max = maxima.getOrNull(index)?.jsonPrimitive?.runCatchingDouble() ?: return@mapIndexedNotNull null
            val min = minima.getOrNull(index)?.jsonPrimitive?.runCatchingDouble() ?: return@mapIndexedNotNull null
            ForecastDay(
                date = date,
                maxC = max,
                minC = min,
                rainChance = rain?.getOrNull(index)?.jsonPrimitive?.runCatchingDouble() ?: 0.0,
                weatherCode = codes?.getOrNull(index)?.jsonPrimitive?.runCatchingDouble(),
            )
        }
    }

    private fun kotlinx.serialization.json.JsonPrimitive.runCatchingDouble(): Double? = runCatching { double }.getOrNull()
}

object FxRateParser {
    /** Parses the quote leg of `pair` (e.g. `USD-INR` → `rates.INR`). */
    fun parse(
        raw: String,
        pair: String,
    ): Double? {
        val (base, _) = split(pair) ?: return null
        // Direct quote only; crosses go through [rateFor] over [parseAll].
        if (!base.equals("USD", ignoreCase = true)) return null
        if (raw.isBlank()) return null
        val quote = pair.substringAfter("-", "").ifBlank { return null }
        val root = runCatching { Http.json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val rates = root["rates"]?.jsonObject ?: return null
        return rates[quote.uppercase()]?.jsonPrimitive?.runCatchingDouble()
    }

    /**
     * Every USD-base quote in the reply (`INR` → 95.5). One fetch feeds every
     * tracked pair; crosses derive from here, never from a second call.
     */
    fun parseAll(raw: String): Map<String, Double> {
        if (raw.isBlank()) return emptyMap()
        val root = runCatching { Http.json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return emptyMap()
        val rates = root["rates"]?.jsonObject ?: return emptyMap()
        return rates
            .mapNotNull { (code, value) ->
                val amount = runCatching { value.jsonPrimitive.double }.getOrNull() ?: return@mapNotNull null
                code.uppercase() to amount
            }.toMap()
    }

    /**
     * The rate for `pair` from one USD-base reply. `USD-INR` reads directly;
     * `EUR-INR` derives as `INR / EUR`. Null when a leg is missing — a missing
     * leg is absent, never zero.
     */
    fun rateFor(
        pair: String,
        usdBase: Map<String, Double>,
    ): Double? {
        val (base, quote) = split(pair) ?: return null
        if (base.equals("USD", ignoreCase = true)) return usdBase[quote.uppercase()]
        if (!quote.equals("INR", ignoreCase = true)) return null
        val inr = usdBase["INR"] ?: return null
        val leg = usdBase[base.uppercase()] ?: return null
        if (leg == 0.0) return null
        return inr / leg
    }

    fun url(): String = "https://api.frankfurter.dev/v1/latest?base=USD&symbols=INR,EUR,GBP"

    fun url(pair: String): String {
        val (_, quote) = split(pair) ?: ("USD" to "INR")
        return "https://api.frankfurter.dev/v1/latest?base=USD&symbols=$quote"
    }

    private fun split(pair: String): Pair<String, String>? {
        val parts = pair.split("-").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size != 2) return null
        return parts[0] to parts[1]
    }

    private fun kotlinx.serialization.json.JsonPrimitive.runCatchingDouble(): Double? = runCatching { double }.getOrNull()
}

object WeatherUrl {
    fun url(
        lat: Double,
        lon: Double,
    ): String =
        "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,relative_humidity_2m,wind_speed_10m,precipitation,weather_code" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code" +
            "&timezone=auto&forecast_days=7"
}
