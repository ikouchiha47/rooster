package com.personalos.app.core.search

/**
 * What a raw Messages query becomes: an FTS5 `MATCH` expression, a literal
 * `LIKE` pattern, or nothing at all.
 *
 * The whole point of this type is that **user input is data, never syntax**.
 * The store binds whichever string this produces as a parameter; the string is
 * assembled here so a stray quote, dash, asterisk or the word `OR` can only be
 * text to find, never a way to change the query.
 *
 * There is deliberately no third mode and no global search: the read these feed
 * is scoped to SMS in SQL (`Sql.EVENTS_SEARCH_MATCH` /
 * `Sql.EVENTS_SEARCH_LIKE`).
 */
sealed interface SmsSearch {
    /** Nothing to look for: what an empty or whitespace-only field produces. */
    data object Blank : SmsSearch

    /**
     * An FTS5 MATCH expression. Each user term is quoted and suffixed with `*`,
     * so terms are looked up as prefixes and adjacent terms are an implicit
     * AND — which, with the trigram tokenizer, is what gives sub-string matching
     * (`rtel` finds `airtel`).
     */
    data class Match(
        val expression: String,
    ) : SmsSearch

    /**
     * A literal `LIKE` pattern for a query the trigram index cannot answer: one
     * that contains a word shorter than three characters. Wrapped in `%` by this
     * builder and used with `ESCAPE '\'` in SQL, so `%` and `_` in the user's
     * text stay literal.
     */
    data class Like(
        val pattern: String,
    ) : SmsSearch
}

/** The shortest term the trigram tokenizer can match. */
private const val TRIGRAM_MIN = 3

/**
 * Turns a raw field value into a [SmsSearch], deciding MATCH vs LIKE vs nothing.
 *
 * **Where this decision lives, and why.** The fallback is a property of the
 * *index*, not of any screen, and not of the SQL: trigram MATCH yields nothing
 * below three characters, so a query containing a shorter word must route to
 * LIKE or it silently returns nothing. Keeping it here means the screen just
 * passes text and the repository just runs the plan, and both can be tested
 * without a database.
 *
 * Input is split on whitespace; surrounding and repeated whitespace is ignored.
 * A query where **every** term is at least [TRIGRAM_MIN] characters uses MATCH;
 * any shorter term sends the whole query to LIKE, because a MATCH requiring an
 * unanswerable term would return nothing even if the other terms would match.
 */
fun smsSearch(raw: String): SmsSearch {
    val terms = raw.split(WHITESPACE).filter { it.isNotEmpty() }
    if (terms.isEmpty()) return SmsSearch.Blank

    if (terms.any { it.length < TRIGRAM_MIN }) {
        val literal = terms.joinToString(" ")
        return SmsSearch.Like("%" + escapeLike(literal) + "%")
    }
    return SmsSearch.Match(terms.joinToString(" ") { matchTerm(it) })
}

private val WHITESPACE = Regex("\\s+")

/**
 * One quoted, prefix `MATCH` term. Doubling `"` is FTS5's own escape for a quote
 * inside a string, so a term cannot terminate early; inside the quotes every
 * other character — `-`, `*`, `(`, `:` — is literal. The trailing `*` makes the
 * term a prefix query, so what was typed from the start of a word still hits.
 */
private fun matchTerm(term: String): String = "\"" + term.replace("\"", "\"\"") + "\"*"

/**
 * Escapes SQL `LIKE`'s three metacharacters so they are matched literally:
 * backslash (the escape character itself, first), `%` and `_`.
 */
private fun escapeLike(literal: String): String =
    literal
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")
