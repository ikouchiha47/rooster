package com.personalos.app.core.tag

/**
 * Rung 0 of the tagger ladder: a compiled lexicon plus source priors.
 *
 * The **vocabulary is injected, not owned** — see [TermSource] and [TermStore].
 * Adding a festival, a sport, or eventually a treaty is a new term source or a data
 * edit; it has never needed to be a change to this file
 * (docs/CODE-DESIGN-GUIDELINES.md §2).
 *
 * Deterministic, offline, and free — it runs per item at ingest. Every term is a
 * word-boundary regex, so "down" does not fire on "download".
 *
 * This is intentionally replaceable: a model-backed implementation drops in behind
 * the same [Tagger] interface without any tile changing.
 */
class HeuristicTagger(
    private val lexicon: () -> Map<String, Regex> = TermStore(listOf(BundledTermSource))::lexicon,
    override val version: Int = 7,
) : Tagger {
    override val id: String = "${TaggerKind.HEURISTIC.name.lowercase()}-v$version"

    override val kind: TaggerKind = TaggerKind.HEURISTIC

    override suspend fun tag(input: TagInput): TagResult {
        val text = input.text.lowercase()
        val tags = linkedSetOf<String>()

        // The source declares what it publishes; we never infer it. Assuming
        // "RSS means news" is what put a status page's outage rows under news.
        tags += input.declaredTags

        val fromAdvert = input.source == SourceKind.SMS && isDltPromo(input.sender)

        if (input.source == SourceKind.SMS) {
            if (fromAdvert) tags += Tags.PROMO
            if (isPersonalSender(input.sender)) tags += Tags.PERSONAL
        }

        for ((tag, pattern) in lexicon()) {
            // Marketing copy is not evidence of subject matter. "Monsoon Sale",
            // "Diwali Dhamaka" and "Cricket Bat Sale" are retail campaigns; letting
            // them claim weather, festival or games is how a phone's promo inbox
            // ended up in the Weather timeline. Natures still fire — only *subject*
            // inference is gated here.
            if (fromAdvert && tag in SUBJECTS) continue
            if (pattern.containsMatchIn(text)) tags += tag
        }

        return TagResult(
            tags = tags,
            confidence = if (tags.isEmpty()) BARE_CONFIDENCE else WORD_CONFIDENCE,
            taggerId = id,
        )
    }

    /** DLT promotional headers end in `-P`; the suffix beats any wording. */
    private fun isDltPromo(sender: String?): Boolean = sender?.trim()?.endsWith("-P", ignoreCase = true) == true

    /**
     * A sender that is a real phone number is a person. DLT business headers
     * (`VM-HDFCBK-S`) and short codes are not.
     */
    private fun isPersonalSender(sender: String?): Boolean {
        val s = sender?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        if (DLT_SUFFIXES.any { s.endsWith(it, ignoreCase = true) }) return false
        return s.count { it.isDigit() } >= MIN_PHONE_DIGITS
    }

    private companion object {
        /** Rung 0 is a keyword match, so confidence is deliberately modest. */
        const val WORD_CONFIDENCE = 0.7f
        const val BARE_CONFIDENCE = 0.2f

        /**
         * Tags that answer "what is this about" (§10.5). A subject is a stronger
         * claim than a nature, so it is the one we refuse to infer from
         * advertising copy.
         */
        val SUBJECTS = setOf(Tags.FINANCE, Tags.TECH, Tags.TRAVEL, Tags.FESTIVAL, Tags.GAMES, Tags.WEATHER, Tags.PAPER)

        /** DLT category suffixes: `-P` promotional, `-S`/`-T`/`-G` transactional or service. */
        val DLT_SUFFIXES = listOf("-P", "-S", "-T", "-G")

        /** At least this many digits means a phone number rather than a short code. */
        const val MIN_PHONE_DIGITS = 10
    }
}
