package com.personalos.app.core.category

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A message category as **data**, not a Kotlin enum. Adding a category is a row
 * in `categorization.v1.json`; no code changes. `color` and `sort` travel with
 * the value so a later UI can render tabs straight from the taxonomy.
 */
@Serializable
data class Category(
    val id: String,
    val label: String,
    val sort: Int = 0,
    val color: String = "#607D8B",
)

/**
 * One categorisation rule: an ordered row. The evaluator sorts by [priority]
 * (ascending; the lowest number wins) and takes the first match.
 *
 * [matcherType] is an **open** string id resolved through a registry of
 * [RuleMatcher] implementations (see [BundledMatchers]) — never a `when`/enum.
 * A new kind of test is a new implementation registered in one place; the
 * evaluator and the other matchers are untouched (Open/Closed, mirroring
 * `KindAdapter`).
 */
@Serializable
data class CategoryRule(
    val id: String,
    val categoryId: String,
    val priority: Int,
    val matcherType: String,
    val pattern: String = "",
    val note: String = "",
)

/**
 * The categorisation taxonomy: which categories exist and the rules that project
 * a message onto one of them. Bundled as an asset and loaded like the tag
 * taxonomy, so behaviour is data the app reads — not code it embeds.
 */
@Serializable
data class CategoryTaxonomy(
    val id: String,
    val version: Int,
    /** Category returned when no rule matches and the [Fallback] abstains. */
    val unknownId: String = "unknown",
    val categories: List<Category> = emptyList(),
    val rules: List<CategoryRule> = emptyList(),
) {
    fun category(id: String): Category? = categories.firstOrNull { it.id == id }

    /** The Unknown category; a minimal placeholder if the seed omits it, so the evaluator never throws. */
    fun unknown(): Category = category(unknownId) ?: Category(unknownId, "Unknown")
}

object CategoryTaxonomyJson {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    fun parse(text: String): CategoryTaxonomy = json.decodeFromString(text)
}
