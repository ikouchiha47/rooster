package com.personalos.app.core.mention

/*
 * The one owner of the Unicode word-boundary form used across the app.
 *
 * Boundaries are explicit Unicode lookarounds, not `\b`: `\b` only knows ASCII
 * word characters, so it never fires around Devanagari (or other Indic)
 * alternates — between a space and `க` there is no `\b` boundary. The
 * lookarounds treat any letter, mark, decimal digit or underscore as a word
 * character instead. The `(?u)` flag turns on Unicode-aware case folding to go
 * with them — lowercase-u, because Android's regex engine rejects the `(?U)`
 * (UNICODE_CHARACTER_CLASS) spelling the JVM accepts, and that mismatch
 * crashed the app on first index build while every unit test stayed green.
 *
 * Two callers share this one definition: [wordPattern] wraps gazetteer
 * literals (places, parties), and `core/rules`' text predicate wraps a
 * user-supplied regex body. Keeping the boundary here means the two can never
 * drift apart.
 */

/** Wrap a regex body in the shared Unicode-aware word boundaries. */
internal fun unicodeBoundaryPattern(regexBody: String): String = "(?u)(?<!$WORD_CHAR)(?:$regexBody)(?!$WORD_CHAR)"

private const val WORD_CHAR = "[\\p{L}\\p{M}\\p{Nd}_]"

/**
 * Builds one case-insensitive alternation out of already-escaped surfaces,
 * longest first so the leftmost match at any position is also the longest.
 *
 * Returns null when there is nothing to match, so an empty vocabulary matches
 * nothing rather than the empty string everywhere.
 */
internal fun wordPattern(escapedLongestFirst: List<String>): Regex? {
    if (escapedLongestFirst.isEmpty()) return null
    return Regex(
        unicodeBoundaryPattern(escapedLongestFirst.joinToString("|")),
        RegexOption.IGNORE_CASE,
    )
}
