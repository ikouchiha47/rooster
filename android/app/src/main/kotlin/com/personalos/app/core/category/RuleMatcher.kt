package com.personalos.app.core.category

import java.util.concurrent.ConcurrentHashMap

/** The message under classification, plus the address-book relationship flag. */
data class Message(
    val sender: String,
    val body: String,
    val isContact: Boolean,
)

/** Sender provenance, computed once per message and shared by the matchers. */
data class Provenance(
    val senderKind: String,
    val dltSuffix: String?,
)

/**
 * A rule predicate. [matcherType] is the open id a [CategoryRule] names; the
 * evaluator resolves it through [matchersByType]. A new kind of test is a new
 * implementation — no existing matcher, and no evaluator branch, changes
 * (mirrors `KindAdapter`).
 */
interface RuleMatcher {
    val matcherType: String

    fun matches(
        rule: CategoryRule,
        message: Message,
        provenance: Provenance,
    ): Boolean
}

fun matchersByType(matchers: List<RuleMatcher>): Map<String, RuleMatcher> = matchers.associateBy { it.matcherType }

/** Matches the address-book flag: the relationship itself is the signal. */
object ContactMatcher : RuleMatcher {
    override val matcherType = "contact"

    override fun matches(
        rule: CategoryRule,
        message: Message,
        provenance: Provenance,
    ): Boolean = message.isContact
}

/** Matches a DLT category suffix (`P`/`S`/`T`/`G`); [CategoryRule.pattern] is the letter. */
object HeaderSuffixMatcher : RuleMatcher {
    override val matcherType = "header_suffix"

    override fun matches(
        rule: CategoryRule,
        message: Message,
        provenance: Provenance,
    ): Boolean = rule.pattern.isNotBlank() && provenance.dltSuffix.equals(rule.pattern, ignoreCase = true)
}

/** Matches a sender-kind id (e.g. `number`), as produced by the [SenderKindRule] registry. */
object SenderKindMatcher : RuleMatcher {
    override val matcherType = "sender_kind"

    override fun matches(
        rule: CategoryRule,
        message: Message,
        provenance: Provenance,
    ): Boolean = provenance.senderKind == rule.pattern
}

/**
 * Matches when any `|`-separated term of [CategoryRule.pattern] appears in the
 * body. Plain ASCII words/phrases match on word boundaries; anything else
 * (symbols such as `%` / `a/c`, or non-Latin scripts) matches as a substring.
 */
object BodyAnyMatcher : RuleMatcher {
    override val matcherType = "body_any"

    private val cache = ConcurrentHashMap<String, List<String>>()

    override fun matches(
        rule: CategoryRule,
        message: Message,
        provenance: Provenance,
    ): Boolean {
        val text = message.body.lowercase()
        return terms(rule.pattern).any { termMatches(text, it) }
    }

    private fun terms(pattern: String): List<String> = cache.getOrPut(pattern) { pattern.split('|').map { it.trim() }.filter { it.isNotEmpty() } }
}

/** Matches when [CategoryRule.pattern], a regular expression, is found in the body. */
object BodyRegexMatcher : RuleMatcher {
    override val matcherType = "body_regex"

    private val cache = ConcurrentHashMap<String, Regex>()

    override fun matches(
        rule: CategoryRule,
        message: Message,
        provenance: Provenance,
    ): Boolean =
        rule.pattern.isNotBlank() &&
            cache.getOrPut(rule.pattern) { Regex(rule.pattern, RegexOption.IGNORE_CASE) }.containsMatchIn(message.body)
}

/**
 * Matches when the sender is of the [CategoryRule.pattern] kind (default
 * `number`) *and* the body reads as human prose rather than a template — the
 * "unsaved number, plain text" rule. A bare code is not human text.
 */
object HumanTextMatcher : RuleMatcher {
    override val matcherType = "human_text"

    override fun matches(
        rule: CategoryRule,
        message: Message,
        provenance: Provenance,
    ): Boolean {
        val kind = rule.pattern.ifBlank { NumberSenderKind.kindId }
        return provenance.senderKind == kind && looksHuman(message.body)
    }
}

/** Human prose: at least two words that carry letters. */
fun looksHuman(body: String): Boolean = body.split(WHITESPACE).count { word -> word.count { it.isLetter() } >= 2 } >= 2

/** The bundled matchers. Bind a new one here (one line); nothing else changes. */
object BundledMatchers {
    val all: List<RuleMatcher> =
        listOf(
            ContactMatcher,
            HeaderSuffixMatcher,
            SenderKindMatcher,
            BodyAnyMatcher,
            BodyRegexMatcher,
            HumanTextMatcher,
        )
}

private val WHITESPACE = Regex("""\s+""")
private val WORD_CACHE = ConcurrentHashMap<String, Regex>()

private fun termMatches(
    text: String,
    term: String,
): Boolean {
    val ascii = term.all { it.code < 0x80 }
    val wordLike = ascii && term.all { it.isLetterOrDigit() || it == ' ' }
    return if (wordLike) {
        WORD_CACHE.getOrPut(term) { wordPattern(term) }.containsMatchIn(text)
    } else {
        text.contains(term)
    }
}

private fun wordPattern(term: String): Regex = Regex("""\b${term.split(' ').joinToString("""\s+""") { Regex.escape(it) }}\b""")
