package com.personalos.app.core.tag

/**
 * Rung 0 of the tagger ladder: lexicons plus source priors.
 *
 * Deterministic, offline, and free - it runs per item at ingest. Every entry is
 * a word-boundary regex, so "down" does not fire on "download" and "ai" does not
 * fire on "said".
 *
 * This is intentionally replaceable: a model-backed implementation drops in
 * behind the same [Tagger] interface without any tile changing.
 */
class HeuristicTagger(
    override val version: Int = 6,
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

        for ((tag, pattern) in LEXICON) {
            // Marketing copy is not evidence of subject matter. "Monsoon Sale",
            // "Monsoon Fest" and "monsoon-ready lookbook" are seasonal retail
            // campaigns; letting them claim the `weather` subject is exactly how a
            // phone's promo inbox ended up in the Weather timeline. Natures still
            // fire - only *subject* inference is gated here.
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
        val SUBJECTS = setOf(Tags.FINANCE, Tags.TECH, Tags.TRAVEL, Tags.FESTIVAL, Tags.WEATHER, Tags.PAPER)

        /** DLT category suffixes: `-P` promotional, `-S`/`-T`/`-G` transactional or service. */
        val DLT_SUFFIXES = listOf("-P", "-S", "-T", "-G")

        /** At least this many digits means a phone number rather than a short code. */
        const val MIN_PHONE_DIGITS = 10

        // Term lists. `_` is a literal space (so multi-word phrases stay phrases)
        // and `|` separates alternatives; [words] turns one into a word-boundary
        // regex. Written as constants to keep the regex construction readable.
        const val WEATHER_WORDS =
            "flood|floods|flooding|cyclone|earthquake|quake|landslide|heatwave|heat_wave|" +
                "monsoon|heavy_rain|rainfall|storm|drought|humidity|temperature|thunderstorm|cold_wave"

        const val FINANCE_WORDS =
            "sensex|nifty|rupee|stock|stocks|share|shares|market|markets|rbi|sebi|inflation|gdp|" +
                "ipo|bond|bonds|mutual_fund|equity|earnings|bse|nse|portfolio|tariff"

        const val TECH_WORDS =
            "gadget|smartphone|semiconductor|chip|chips|software|developer|cybersecurity|robot|" +
                "robotics|artificial_intelligence|machine_learning|startup|cloud|firmware"

        const val EXPENSE_WORDS =
            "bill|bills|recharge|debited|credited|upi|payment|paid|invoice|emi|subscription|" +
                "renewal|grocery|groceries|rent|utilities|wallet"

        const val TRAVEL_WORDS =
            "travel|flight|flights|train|railway|tourism|tourist|visa|hotel|holiday|vacation|" +
                "festival|carnival|itinerary|airline|bandh|strike"

        const val PROMO_WORDS =
            "offer|offers|discount|cashback|sale|coupon|promo_code|flat_off|freebie|" +
                "limited_period|buy_one_get_one"

        /**
         * A *nature*: something went wrong. Deliberately includes disasters, not
         * just service outages - "floods hit 49 lakh people" is an incident as much
         * as a downtime notice is, and an item can carry both `weather` (subject)
         * and `incident` (nature).
         */
        const val INCIDENT_WORDS =
            "outage|downtime|maintenance|disrupted|disruption|restored|service_interruption|" +
                "degradation|not_working|down|flood|floods|flooding|cyclone|earthquake|landslide|" +
                "evacuated|evacuation|casualties|death_toll|relief_camp|washed_away|submerged|inundated"

        /**
         * A curated seed of festival names.
         *
         * Festivals are the one vocabulary that cannot be inferred from language
         * alone, which is why research concluded the region mapping has to be
         * hand-curated (docs/research/place-inference-signals.md §3.4) - the words
         * below are that curation's first slice. Region mapping comes later.
         *
         * `puja`/`pujas` are included bare because Indian headlines pluralise them
         * ("Durga Pujas", "Durga Puja kicks up a storm") and a bare `durga_puja`
         * misses the plural. The trade-off: `Puja` is also a given name, so a rare
         * headline about a person named Puja will be tagged as a festival.
         */
        const val FESTIVAL_WORDS =
            "durga_puja|durga_pujas|puja|pujas|diwali|deepavali|pongal|onam|bihu|ganesh_chaturthi|" +
                "navratri|dussehra|dasara|holi|eid|ramadan|christmas|lohri|makara_sankranti|ugadi|" +
                "gudi_padwa|vishu|baisakhi|raksha_bandhan|janmashtami|maha_shivratri|chhath|" +
                "karva_chauth|kumbh_mela|rath_yatra"

        const val PAPER_WORDS =
            "arxiv|preprint|doi|peer-reviewed|journal|dataset|methodology|researchers|empirical"

        const val OFFICIAL_WORDS =
            "circular|notification|advisory|gazette|government_order|tender|public_notice|press_release"

        fun words(alternatives: String): Regex = Regex("""\b(?:${alternatives.replace('_', ' ')})\b""", RegexOption.IGNORE_CASE)

        val LEXICON: Map<String, Regex> =
            mapOf(
                Tags.WEATHER to words(WEATHER_WORDS),
                Tags.FINANCE to words(FINANCE_WORDS),
                Tags.TECH to words(TECH_WORDS),
                Tags.EXPENSE to words(EXPENSE_WORDS),
                Tags.TRAVEL to words(TRAVEL_WORDS),
                Tags.FESTIVAL to words(FESTIVAL_WORDS),
                Tags.PROMO to words(PROMO_WORDS),
                Tags.INCIDENT to words(INCIDENT_WORDS),
                Tags.PAPER to words(PAPER_WORDS),
                Tags.OFFICIAL to words(OFFICIAL_WORDS),
            )
    }
}
