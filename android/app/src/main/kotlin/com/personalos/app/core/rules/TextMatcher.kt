package com.personalos.app.core.rules

import com.personalos.app.core.mention.unicodeBoundaryPattern

/** Which stored text a [TextMatcher] reads. */
enum class TextTarget(
    val serialName: String,
) {
    TITLE("title"),
    CONTENT("content"),
    ANY("any"),
    ;

    companion object {
        fun from(value: String): TextTarget? = entries.firstOrNull { it.serialName == value }
    }
}

/**
 * A text predicate: a user pattern compiled once, with Unicode-aware word
 * boundaries and the `(?u)` flag (ADR 0003 §12, plan T2.4). The boundaries come
 * from [unicodeBoundaryPattern] — the same definition the gazetteers use — so
 * `\b`'s ASCII-only blind spot around Devanagari cannot reappear here.
 *
 * **ReDoS.** The pattern is capped at [MAX_PATTERN_LENGTH] at parse, and [matches]
 * examines at most [MAX_TEXT_LENGTH] characters of the input. That is the bound
 * ADR §12 asks for: a user pattern cannot scan an unbounded body. It bounds the
 * *input*, not the engine's worst case, so a deliberately catastrophic pattern
 * can still be slow on an input short enough to pass the bound; the cap is what
 * keeps that input small and ingest from stalling on real payloads. A pattern
 * that recurses once per repetition can also exhaust the stack on a long input,
 * which [matches] treats as a non-match rather than a crash.
 */
data class TextMatcher(
    val pattern: String,
    val target: TextTarget,
) {
    /** Compiled once, at construction. */
    val regex: Regex = Regex(unicodeBoundaryPattern(pattern), RegexOption.IGNORE_CASE)

    /**
     * True when [text] contains a bounded, boundary-wrapped match.
     *
     * Only the first [MAX_TEXT_LENGTH] characters are examined; anything past
     * the bound is left unscanned rather than fed to the engine.
     */
    fun matches(text: String): Boolean {
        if (text.isEmpty()) return false
        val bounded = if (text.length <= MAX_TEXT_LENGTH) text else text.substring(0, MAX_TEXT_LENGTH)
        return try {
            regex.containsMatchIn(bounded)
        } catch (_: StackOverflowError) {
            // A pattern that recurses once per repetition (e.g. `(a|aa)+`) can
            // exhaust the stack on a long run. That is a non-match, not a crash
            // of ingest; a linear-time engine would remove the need to catch it.
            false
        }
    }

    companion object {
        const val MAX_PATTERN_LENGTH = 1_000
        const val MAX_TEXT_LENGTH = 20_000
    }
}
