package com.personalos.app.core.hazard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pure parser for the USGS GeoJSON summary feed.
 *
 * USGS supplies `[longitude, latitude, depth]` in `geometry.coordinates`, so the
 * axes are deliberately not the `[lat, lon]` order the rest of the app uses.
 * Anything that is not a usable point is skipped rather than guessed at: a
 * feature with a null magnitude is valid, but a non-point geometry is not a
 * location we can draw.
 */
object QuakeGeoJsonParser {
    private val json = Json { ignoreUnknownKeys = true }

    const val EVENT_PAGE = "https://earthquake.usgs.gov/earthquakes/eventpage/"

    fun parse(raw: String): List<Quake> {
        if (raw.isBlank()) return emptyList()

        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return emptyList()
        val features = root["features"] as? JsonArray ?: return emptyList()

        return features.mapNotNull { feature -> (feature as? JsonObject)?.let(::parseFeature) }
    }

    private fun parseFeature(feature: JsonObject): Quake? {
        val geometry = feature["geometry"] as? JsonObject ?: return null
        if (geometry.text("type") != "Point") return null

        val coordinates = geometry["coordinates"] as? JsonArray ?: return null
        if (coordinates.size < 2) return null
        val lon = coordinates[0].doubleOrNull() ?: return null
        val lat = coordinates[1].doubleOrNull() ?: return null
        if (!lat.isFinite() || !lon.isFinite()) return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null

        val id = feature.text("id")?.takeIf { it.isNotBlank() } ?: return null
        val properties = feature["properties"] as? JsonObject ?: return null
        val magnitude = properties["mag"].doubleOrNull()?.takeIf { it.isFinite() }
        val time = properties["time"].longOrNull() ?: 0L
        val place = properties.text("place")?.takeIf { it.isNotBlank() } ?: "Unknown location"
        val url = properties.text("url")?.takeIf { it.isNotBlank() } ?: "$EVENT_PAGE$id"

        return Quake(
            id = id,
            magnitude = magnitude,
            place = place,
            time = time,
            lat = lat,
            lon = lon,
            depth = coordinates.getOrNull(2).doubleOrNull()?.takeIf { it.isFinite() },
            url = url,
        )
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull()

    private fun JsonElement?.doubleOrNull(): Double? = (this as? JsonPrimitive)?.content?.toDoubleOrNull()

    private fun JsonElement?.longOrNull(): Long? = (this as? JsonPrimitive)?.content?.toLongOrNull()

    /** `JsonNull` is a [JsonPrimitive] whose content is the string "null". */
    private fun JsonPrimitive.contentOrNull(): String? = content.takeUnless { this is kotlinx.serialization.json.JsonNull }
}
