package com.personalos.app.core.enrich

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Pulls a readable summary out of an article page.
 *
 * Uses **jsoup** rather than hand-rolled regex. The input here is arbitrary
 * third-party HTML - the messiest input in the app - and the deciding factor was
 * entity handling: HTML defines ~2,231 named character references, and a
 * hand-written map covers a handful and silently drops the rest (`&eacute;`,
 * `&minus;`, …). jsoup decodes all of them, tolerates malformed markup, and
 * still runs on the JVM so this stays unit-testable.
 *
 * It exists because some feeds ship an empty `<description>` - Indian Express is
 * entirely empty, `<![CDATA[]]>` and all - so the only way to get a summary is
 * to read the page.
 *
 * Precedence, best first:
 *  1. `og:description` - written for sharing, usually a clean one-liner
 *  2. `<meta name="description">`
 *  3. the first substantive paragraph
 *
 * Verified against real pages (2026-09-14): Indian Express articles carry all
 * three, with `og:description` between 104 and 339 characters.
 */
object ArticleSummary {
    /** Anything shorter is boilerplate ("Read more", "Subscribe"), not a summary. */
    const val MIN_LENGTH = 80

    /** Trim to keep a stored summary sane; the UI clamps for display anyway. */
    const val MAX_LENGTH = 600

    private val WHITESPACE = Regex("\\s+")

    /** Returns a cleaned summary, or null when the page yields nothing usable. */
    fun extract(html: String): String? {
        val doc = Jsoup.parse(html)

        metaContent(doc, "og:description")
            ?.takeIf { it.length >= MIN_LENGTH }
            ?.let { return it }

        metaContent(doc, "description")
            ?.takeIf { it.length >= MIN_LENGTH }
            ?.let { return it }

        return doc
            .select("p")
            .map { it.text().clean() }
            .firstOrNull { it.length >= MIN_LENGTH }
    }

    /**
     * Reads a `<meta>` value by `property` or `name`, **case-insensitively**.
     * Pages disagree on both the attribute and the casing (`og:description` vs
     * `og:Description`), so neither is trusted to be exact.
     */
    private fun metaContent(
        doc: Document,
        key: String,
    ): String? =
        doc.select("meta").firstNotNullOfOrNull { element ->
            val declared = element.attr("property").ifBlank { element.attr("name") }
            if (declared.equals(key, ignoreCase = true)) {
                element.attr("content").clean().takeIf { it.isNotEmpty() }
            } else {
                null
            }
        }

    /**
     * Collapses whitespace and caps length.
     *
     * `\u00A0` is normalised explicitly: jsoup decodes `&nbsp;` to a
     * non-breaking space, which `\s` does **not** match, so without this a
     * summary would carry invisible non-breaking spaces through to the UI.
     */
    private fun String.clean(): String =
        replace('\u00A0', ' ')
            .replace(WHITESPACE, " ")
            .trim()
            .take(MAX_LENGTH)
}
