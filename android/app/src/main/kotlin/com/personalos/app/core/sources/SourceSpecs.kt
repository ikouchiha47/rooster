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
sealed interface SourceSpec {
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

object SourceSpecs {
    private val json = Json { ignoreUnknownKeys = false }

    /**
     * Parses and validates `spec_json` for [kind].
     *
     * Required keys per ADR 0003; unknown keys are rejected so a future kind
     * cannot silently masquerade as a v1 kind. Missing `tags` defaults to
     * `news` (ADR 0003 §Spec shapes).
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
     * @throws IllegalArgumentException when the kind is unknown or the spec is invalid.
     */
    fun parse(
        kindName: String,
        specJson: String,
    ): SourceSpec {
        val kind = SourceKind.from(kindName) ?: throw IllegalArgumentException("unknown source kind: $kindName")
        return parse(kind, specJson)
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
