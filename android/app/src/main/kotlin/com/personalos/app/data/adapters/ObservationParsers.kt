package com.personalos.app.data.adapters

import com.personalos.app.core.net.Http
import kotlinx.serialization.json.double
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
    )

    fun parse(raw: String): Current? {
        if (raw.isBlank()) return null
        val root = runCatching { Http.json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val current = root["current"]?.jsonObject ?: return null
        val temp = current["temperature_2m"]?.jsonPrimitive?.doubleOrNull() ?: return null
        val rain = current["precipitation"]?.jsonPrimitive?.doubleOrNull() ?: 0.0
        return Current(tempC = temp, rainMm = rain)
    }

    private fun kotlinx.serialization.json.JsonPrimitive.doubleOrNull(): Double? = runCatching { double }.getOrNull()
}

object FxRateParser {
    /** Parses the quote leg of `pair` (e.g. `USD-INR` → `rates.INR`). */
    fun parse(
        raw: String,
        pair: String,
    ): Double? {
        if (raw.isBlank()) return null
        val quote = pair.substringAfter("-", "").ifBlank { return null }
        val root = runCatching { Http.json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val rates = root["rates"]?.jsonObject ?: return null
        return rates[quote]?.jsonPrimitive?.runCatchingDouble()
    }

    fun url(pair: String): String {
        val (base, quote) = pair.split("-").map { it.trim().uppercase() }.let { it.getOrElse(0) { "USD" } to it.getOrElse(1) { "INR" } }
        return "https://api.frankfurter.dev/v1/latest?base=$base&symbols=$quote"
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
            "&current=temperature_2m,precipitation" +
            "&timezone=auto"
}
