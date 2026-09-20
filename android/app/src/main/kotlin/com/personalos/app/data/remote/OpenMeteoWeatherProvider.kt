package com.personalos.app.data.remote

import android.util.Log
import com.personalos.app.core.Chars
import com.personalos.app.core.cache.StringCache
import com.personalos.app.core.model.WeatherDay
import com.personalos.app.core.model.WeatherSnapshot
import com.personalos.app.core.net.Http
import com.personalos.app.core.provider.WeatherProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

/** A place to fetch weather for. Coordinates are bundled (no runtime geocoding). */
data class WeatherLocation(
    val name: String,
    val lat: Double,
    val lon: Double,
)

/**
 * Open-Meteo (free, keyless, attribution required).
 *
 * Caching behaviour:
 *  - a **whole week** of forecast is fetched per location and stored, so the
 *    screen never triggers a network call just because it was opened;
 *  - cached data is emitted first (cards fill immediately, no late pop-in);
 *  - a refresh happens only when the cached entry is older than [refreshAfterMs].
 *
 * One snapshot per location is shown; the rest of the week stays cached for
 * later (charts, forecast pages).
 */
class OpenMeteoWeatherProvider(
    private val locations: List<WeatherLocation>,
    private val cache: StringCache,
    private val refreshAfterMs: Long = REFRESH_MS,
) : WeatherProvider {
    override fun observe(): Flow<List<WeatherSnapshot>> =
        flow {
            val now = System.currentTimeMillis()

            // 1. Whatever we already have, immediately.
            val cached = locations.map { cache.read(key(it)) }
            if (cached.all { it != null }) {
                emit(locations.mapIndexed { i, loc -> map(loc, cached[i]!!.value) })
            }

            // 2. Refresh only the stale ones.
            var refreshed = false
            val raws =
                locations.map { loc ->
                    val entry = cache.read(key(loc))
                    if (entry != null && now - entry.at < refreshAfterMs) {
                        entry.value
                    } else {
                        val fetched = runCatching { fetch(loc) }.getOrNull()
                        if (fetched != null) {
                            cache.write(key(loc), fetched, now)
                            refreshed = true
                            fetched
                        } else {
                            entry?.value
                        }
                    }
                }

            if (refreshed || cached.any { it == null }) {
                emit(locations.mapIndexed { i, loc -> map(loc, raws[i].orEmpty()) })
            }
        }.flowOn(Dispatchers.IO)

    override fun forecast(place: String): Flow<List<WeatherDay>> =
        flow {
            // Configured places are looked up by identity; anything else (the
            // present location, whose name is a fixed label and whose cache key
            // is name-based) is served straight from the cache. Cache only either
            // way - a forecast must never block on the network.
            val location = locations.firstOrNull { it.name == place }
            val raw =
                if (location != null) {
                    cache.read(key(location))?.value
                } else {
                    cache.read(keyFor(place))?.value
                }
            emit(parseDays(raw.orEmpty()))
        }

    /**
     * Parses the `daily` block of an Open-Meteo response.
     *
     * Pure, so it is unit-testable without the network. Missing or null entries
     * degrade to 0 rather than throwing: a partial week beats no week.
     */
    internal fun parseDays(raw: String): List<WeatherDay> {
        if (raw.isBlank()) return emptyList()

        val root =
            runCatching { Http.json.parseToJsonElement(raw).jsonObject }.getOrNull()
                ?: return emptyList()
        val daily = root["daily"]?.jsonObject ?: return emptyList()
        val dates = daily["time"]?.jsonArray?.mapNotNull { it.asString() } ?: return emptyList()
        val maxima = daily["temperature_2m_max"]?.jsonArray ?: return emptyList()
        val minima = daily["temperature_2m_min"]?.jsonArray ?: return emptyList()
        val rain = daily["precipitation_probability_max"]?.jsonArray
        val codes = daily["weather_code"]?.jsonArray

        return dates.mapIndexed { index, date ->
            WeatherDay(
                date = date,
                maxC = maxima.getOrNull(index).asDouble()?.roundToInt() ?: 0,
                minC = minima.getOrNull(index).asDouble()?.roundToInt() ?: 0,
                rainChance = rain?.getOrNull(index).asInt() ?: 0,
                weatherCode = codes?.getOrNull(index)?.asInt(),
            )
        }
    }

    private fun JsonElement?.asString(): String? = this?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

    private fun JsonElement?.asDouble(): Double? = this?.let { runCatching { it.jsonPrimitive.double }.getOrNull() }

    private fun JsonElement?.asInt(): Int? = this?.let { runCatching { it.jsonPrimitive.int }.getOrNull() }

    /**
     * Weather for an arbitrary coordinate - used for the device's **present
     * location**, which is not a configured place.
     *
     * Same cache policy as [observe]: serve what we already have, refetch only
     * when stale, and fall back to the stale copy rather than showing nothing.
     * No reverse geocoding happens (ADR 0001), so the caller supplies the label.
     */
    suspend fun snapshotAt(location: WeatherLocation): WeatherSnapshot =
        withContext(Dispatchers.IO) {
            val entry = cache.read(key(location))
            val now = System.currentTimeMillis()

            if (entry != null && now - entry.at < refreshAfterMs) {
                return@withContext map(location, entry.value)
            }

            val fetched = runCatching { fetch(location) }.getOrNull()
            if (fetched != null) {
                cache.write(key(location), fetched, now)
                map(location, fetched)
            } else {
                entry?.let { map(location, it.value) } ?: unavailable(location)
            }
        }

    private fun key(location: WeatherLocation) = keyFor(location.name)

    /** Shared with the observation adapter, so both read one cached body. */
    private fun keyFor(name: String) = cacheKey(name)

    private fun fetch(location: WeatherLocation): String {
        val url =
            buildString {
                append("https://api.open-meteo.com/v1/forecast")
                append("?latitude=").append(location.lat)
                append("&longitude=").append(location.lon)
                append("&current=temperature_2m,relative_humidity_2m,wind_speed_10m")
                append("&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max")
                append("&timezone=auto&forecast_days=").append(FORECAST_DAYS)
            }
        return Http.getText(url)
    }

    private fun map(
        location: WeatherLocation,
        raw: String,
    ): WeatherSnapshot {
        if (raw.isBlank()) return unavailable(location)
        return try {
            val root = Http.json.parseToJsonElement(raw).jsonObject
            val current = root["current"]?.jsonObject
            val temperature = current?.get("temperature_2m")?.jsonPrimitive?.double
            val humidity = current?.get("relative_humidity_2m")?.jsonPrimitive?.int
            val wind = current?.get("wind_speed_10m")?.jsonPrimitive?.double
            val rain =
                root["daily"]
                    ?.jsonObject
                    ?.get("precipitation_probability_max")
                    ?.jsonArray
                    ?.firstOrNull()
                    ?.jsonPrimitive
                    ?.int

            WeatherSnapshot(
                place = location.name,
                temperatureC = (temperature ?: 0.0).roundToInt(),
                summary = if ((rain ?: 0) >= 50) "Rain $rain%" else "Dry",
                detail =
                    listOfNotNull(
                        humidity?.let { "$it%" },
                        wind?.let { "wind ${"%.0f".format(it)} km/h" },
                    ).joinToString(" ${Chars.MIDDLE_DOT} "),
                lat = location.lat,
                lon = location.lon,
            )
        } catch (e: Exception) {
            Log.w(TAG, "map failed for ${location.name}", e)
            unavailable(location)
        }
    }

    private fun unavailable(location: WeatherLocation) =
        WeatherSnapshot(
            place = location.name,
            temperatureC = 0,
            summary = "Unavailable",
            detail = "no data ${Chars.MIDDLE_DOT} check connection",
            lat = location.lat,
            lon = location.lon,
        )

    companion object {
        private const val TAG = "Weather"
        private const val FORECAST_DAYS = 7

        /**
         * Refresh window, in ms. The observation adapter reads through the same
         * window and the same cache key, so a place is fetched once per window
         * no matter how many readers ask for it.
         */
        const val REFRESH_MS = 6L * 60 * 60 * 1000

        fun cacheKey(place: String) = "weather:$place"
    }
}
