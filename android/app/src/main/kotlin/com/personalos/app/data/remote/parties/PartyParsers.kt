package com.personalos.app.data.remote.parties

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.Normalizer

/**
 * One party row read off a Wikipedia list page, before it meets the registry.
 *
 * Everything here is strings-as-scraped; [PartySyncer] decides whether the row
 * updates an existing slug (by name or shared alias) or inserts under a new
 * one. Parsers never invent ids, and never emit leaders as rows — leaders are
 * persons, a later pipeline's job.
 */
data class ParsedParty(
    /** English display name, the join key. Blank rows are skipped, never stored. */
    val englishName: String,
    /** Table code/abbreviation (`UNIÃO`, `KPRF`, `ANC`), null when the table has none. */
    val code: String?,
    /** Tier label for [com.personalos.app.data.PartyEntity.recognition]. */
    val recognition: String,
    /** State-level association when the table states one, else "". */
    val stronghold: String = "",
)

/**
 * A country's list page. One implementation per country because every page
 * tiers and lays out its tables differently — what is shared (tier-table
 * targeting, slug rules) lives here as helpers.
 */
interface CountryPartyParser {
    val country: String

    fun parse(doc: Document): List<ParsedParty>
}

/**
 * Slug derivation for rows the registry has never seen. Prefers the table
 * code (`UNIÃO` → `uniao`); falls back to the English name. Slugs are ids,
 * not match surfaces, so short codes are fine here — the length rule lives in
 * `PartyLexicon`, not in identity.
 */
fun slugish(raw: String): String {
    val folded =
        Normalizer
            .normalize(raw.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
    return folded
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
}

/**
 * The representation-tier tables: wikitables whose header row carries every
 * keyword in [headerHas], excluding tables that sit under a heading containing
 * any of [stopWords (tails: municipal-only, historical, defunct,
 * no-ballot-access) and, when [onlyHeadings] is given, keeping only tables
 * under a heading containing one of those.
 *
 * Heading matching is by substring on the nearest preceding heading because
 * exact span ids cannot be verified without the live markup in hand — and a
 * renamed id must degrade to an empty parse (no rows, sync no-ops) rather
 * than a wrong parse, which the fixture tests pin down per country.
 */
fun tierTables(
    doc: Document,
    headerHas: Set<String>,
    stopWords: Set<String> = emptySet(),
    onlyHeadings: Set<String> = emptySet(),
): List<Element> =
    doc.select("table.wikitable").filter { table ->
        val head =
            table
                .selectFirst("tr")
                ?.text()
                ?.lowercase()
                .orEmpty()
        if (!headerHas.all { it in head }) return@filter false
        val heading = nearestHeading(table)?.lowercase().orEmpty()
        if (stopWords.any { it in heading }) return@filter false
        if (onlyHeadings.isNotEmpty() && onlyHeadings.none { it in heading }) return@filter false
        true
    }

/** Nearest preceding heading text, walking up through nesting. */
fun nearestHeading(el: Element): String? {
    var node: Element? = el
    while (node != null) {
        var sib = node.previousElementSibling()
        while (sib != null) {
            if (sib.tagName() in setOf("h1", "h2", "h3", "h4")) return sib.text()
            sib = sib.previousElementSibling()
        }
        node = node.parent()
    }
    return null
}

/** Header cell texts of a table's first row, lowercased, for column finding. */
fun headerCells(table: Element): List<String> =
    table
        .selectFirst("tr")
        ?.select("th, td")
        ?.map { it.text().lowercase() }
        .orEmpty()

/** Data rows: every row after the header that actually holds cells. */
fun bodyRows(table: Element): List<Element> =
    table
        .select("tr")
        .drop(1)
        .filter { it.select("th, td").isNotEmpty() }

/** First cell text stripped of reference markers — the English name column. */
fun rowName(row: Element): String =
    row
        .select("th, td")
        .firstOrNull()
        ?.text()
        ?.substringBefore('[')
        ?.trim()
        .orEmpty()

/** Bold code-like token in the row (`PL`, `UNIÃO`, `KPRF`), if any. */
fun rowCode(row: Element): String? {
    val bold =
        row
            .select("b")
            .map { it.text().trim() }
            .firstOrNull { it.length in 2..8 && it.any(Char::isLetter) }
    return bold?.takeIf { it.length >= 2 }
}

/** Cell under the first header containing [keyword], else "". */
fun cellUnder(
    row: Element,
    headers: List<String>,
    keyword: String,
): String {
    val idx = headers.indexOfFirst { keyword in it }
    if (idx < 0) return ""
    return row
        .select("th, td")
        .getOrNull(idx)
        ?.text()
        ?.substringBefore('[')
        ?.trim()
        .orEmpty()
}
