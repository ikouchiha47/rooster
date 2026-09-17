package com.personalos.app.core.sources

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray

/**
 * Portable sources unit (ADR 0003): per-kind spec parsing and validation.
 *
 * Pure Kotlin, zero `android.*` imports — see [SourceKind]. Every failure throws
 * [IllegalArgumentException] (unknown kind, malformed JSON, missing or invalid
 * keys), so writers validate with a single call and tests assert one type.
 */
interface SourceSpec {
    /** Subject/nature tags applied at ingest. Never empty: writers default to `news`. */
    val tags: Set<String>
}

/** `kind = "rss"`: poll a plain feed URL directly. */
data class RssSpec(
    val url: String,
    override val tags: Set<String>,
) : SourceSpec

/** `kind = "search"`: a Google News query, polled as RSS. */
data class SearchSpec(
    val query: String,
    /** Language of [query] itself (`en` | `hi` in v1), not the edition queried. */
    val queryLangCode: String,
    /** Which GNews edition to hit (e.g. `en-IN`). v1 seeds are all `en-IN`. */
    val sourceLocale: String,
    override val tags: Set<String>,
) : SourceSpec

/** `kind = "sms"`: the device inbox. No fetch spec; tags default. */
data class SmsSpec(
    override val tags: Set<String> = setOf("news"),
) : SourceSpec

/** `kind = "weather"`: one place, bundled coordinates (no geocoding). */
data class WeatherSpec(
    val place: String,
    val lat: Double,
    val lon: Double,
    override val tags: Set<String> = setOf("weather"),
) : SourceSpec

/** `kind = "fx"`: one currency pair, e.g. `USD-INR`. */
data class FxSpec(
    val pair: String,
    override val tags: Set<String> = setOf("finance"),
) : SourceSpec

/** `kind = "device"`: one local signal, e.g. `battery`. */
data class DeviceSpec(
    val signal: String,
    override val tags: Set<String> = setOf("news"),
) : SourceSpec

object SourceSpecs {
    private val json = Json { ignoreUnknownKeys = false }

    /**
     * Parses and validates `spec_json` for [kind].
     *
     * Required keys per ADR 0002 "Spec shapes (v1)" — the flat shape v1 stored,
     * not ADR 0003 §5's `fetch`/`page`/`map`, which slice 4 introduces. Unknown
     * keys are rejected so a future kind cannot silently masquerade as a v1
     * kind. Missing `tags` defaults to `news`.
     *
     * @throws IllegalArgumentException on any invalid input.
     */
    fun parse(
        kind: SourceKind,
        specJson: String,
    ): SourceSpec =
        when (kind) {
            SourceKind.RSS -> parseRss(obj(specJson, "rss"))
            SourceKind.SEARCH -> parseSearch(obj(specJson, "search"))
        }

    /**
     * Parses and validates `spec_json` for a stored kind string.
     *
     * ADR 0005: `kind` is a string, not the closed [SourceKind] enum. Each kind
     * owns its spec shape; unknown kinds throw so writers fail closed, while
     * sync-all skips unbound kinds before ever parsing (REQ-ING-05).
     *
     * @throws IllegalArgumentException when the kind is unknown or the spec is invalid.
     */
    fun parse(
        kindName: String,
        specJson: String,
    ): SourceSpec =
        when (kindName) {
            SourceKind.RSS.serialName -> parseRss(obj(specJson, "rss"))
            SourceKind.SEARCH.serialName -> parseSearch(obj(specJson, "search"))
            "sms" -> parseSms(obj(specJson, "sms"))
            "weather" -> parseWeather(obj(specJson, "weather"))
            "fx" -> parseFx(obj(specJson, "fx"))
            "device" -> parseDevice(obj(specJson, "device"))
            else -> throw IllegalArgumentException("unknown source kind: $kindName")
        }

    private fun obj(
        specJson: String,
        kind: String,
    ): JsonObject {
        val element = json.parseToJsonElement(specJson)
        require(element is JsonObject) { "kind $kind: spec must be a JSON object" }
        return element
    }

    private fun parseRss(obj: JsonObject): RssSpec {
        rejectUnknown(obj, setOf("url", "tags"), "rss")
        val url = obj.string("url", "rss") ?: throw IllegalArgumentException("kind rss: missing required key url")
        require(url.isNotBlank()) { "kind rss: url must not be blank" }
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "kind rss: url must be http(s): $url"
        }
        return RssSpec(url = url, tags = tags(obj, "rss"))
    }

    private fun parseSearch(obj: JsonObject): SearchSpec {
        rejectUnknown(obj, setOf("query", "query_lang_code", "source_locale", "tags"), "search")
        val query =
            obj.string("query", "search")
                ?: throw IllegalArgumentException("kind search: missing required key query")
        require(query.isNotBlank()) { "kind search: query must not be blank" }
        val lang =
            obj.string("query_lang_code", "search")
                ?: throw IllegalArgumentException("kind search: missing required key query_lang_code")
        require(lang == "en" || lang == "hi") {
            "kind search: query_lang_code must be en or hi, was $lang"
        }
        val locale =
            obj.string("source_locale", "search")
                ?: throw IllegalArgumentException("kind search: missing required key source_locale")
        require(locale.isNotBlank()) { "kind search: source_locale must not be blank" }
        return SearchSpec(
            query = query,
            queryLangCode = lang,
            sourceLocale = locale,
            tags = tags(obj, "search"),
        )
    }

    private fun parseSms(obj: JsonObject): SmsSpec {
        rejectUnknown(obj, setOf("tags"), "sms")
        return SmsSpec(tags = tags(obj, "sms"))
    }

    private fun parseWeather(obj: JsonObject): WeatherSpec {
        rejectUnknown(obj, setOf("place", "lat", "lon", "tags"), "weather")
        val place = obj.string("place", "weather") ?: throw IllegalArgumentException("kind weather: missing required key place")
        require(place.isNotBlank()) { "kind weather: place must not be blank" }
        val lat = obj.double("lat", "weather") ?: throw IllegalArgumentException("kind weather: missing required key lat")
        val lon = obj.double("lon", "weather") ?: throw IllegalArgumentException("kind weather: missing required key lon")
        return WeatherSpec(place = place, lat = lat, lon = lon, tags = tags(obj, "weather"))
    }

    private fun parseFx(obj: JsonObject): FxSpec {
        rejectUnknown(obj, setOf("pair", "tags"), "fx")
        val pair = obj.string("pair", "fx") ?: throw IllegalArgumentException("kind fx: missing required key pair")
        require(pair.isNotBlank()) { "kind fx: pair must not be blank" }
        return FxSpec(pair = pair, tags = tags(obj, "fx"))
    }

    private fun parseDevice(obj: JsonObject): DeviceSpec {
        rejectUnknown(obj, setOf("signal", "tags"), "device")
        val signal = obj.string("signal", "device") ?: throw IllegalArgumentException("kind device: missing required key signal")
        require(signal.isNotBlank()) { "kind device: signal must not be blank" }
        return DeviceSpec(signal = signal, tags = tags(obj, "device"))
    }

    private fun rejectUnknown(
        obj: JsonObject,
        allowed: Set<String>,
        kind: String,
    ) {
        val unknown = obj.keys - allowed
        require(unknown.isEmpty()) { "kind $kind: unknown keys rejected: ${unknown.sorted()}" }
    }

    private fun JsonObject.string(
        key: String,
        kind: String,
    ): String? {
        val element = this[key] ?: return null
        require(element is JsonPrimitive && element.isString) {
            "kind $kind: $key must be a string"
        }
        return element.content
    }

    private fun JsonObject.double(
        key: String,
        kind: String,
    ): Double? {
        val element = this[key] ?: return null
        require(element is JsonPrimitive) {
            "kind $kind: $key must be a number"
        }
        return element.content.toDoubleOrNull()
            ?: throw IllegalArgumentException("kind $kind: $key must be a number")
    }

    private fun tags(
        obj: JsonObject,
        kind: String,
    ): Set<String> {
        val element = obj["tags"] ?: return setOf(DEFAULT_TAG)
        require(element is JsonArray) { "kind $kind: tags must be an array" }
        val tags =
            element.jsonArray
                .map {
                    require(it is JsonPrimitive && it.isString) {
                        "kind $kind: every tag must be a string"
                    }
                    it.content.trim()
                }.filter { it.isNotEmpty() }
                .toSet()
        return tags.ifEmpty { setOf(DEFAULT_TAG) }
    }

    private const val DEFAULT_TAG = "news"
}
