package com.personalos.app.core.tag

/**
 * A source of terms for tags.
 *
 * **Extend tagging by adding an implementation, never by editing
 * [HeuristicTagger]** — see docs/CODE-DESIGN-GUIDELINES.md §2. Before this existed,
 * adding a festival meant editing a `const val` inside the tagger, bumping its
 * version and re-tagging the whole store.
 *
 * [terms] maps tag → alternatives. `_` is a literal space (so `world_cup` means
 * "world cup") and `|` separates alternatives.
 */
interface TermSource {
    val id: String

    fun terms(): Map<String, String>
}

/**
 * The vocabulary that ships with the app: general subjects, natures, festival
 * names, and games.
 *
 * Festivals and games are the two vocabularies that **cannot** be inferred from
 * language alone — no amount of lexical skill derives "Onam" or "MotoGP". They are
 * hand-curated here, and are the reason this file exists: a user-editable or
 * remotely-refreshed source can now supply them instead without touching the
 * tagger.
 */
object BundledTermSource : TermSource {
    override val id: String = "bundled-v1"

    override fun terms(): Map<String, String> = TERMS

    private val TERMS: Map<String, String> =
        mapOf(
            Tags.WEATHER to
                "flood|floods|flooding|cyclone|earthquake|quake|landslide|heatwave|heat_wave|" +
                "monsoon|heavy_rain|rainfall|storm|drought|humidity|temperature|thunderstorm|cold_wave",
            Tags.FINANCE to
                "sensex|nifty|rupee|stock|stocks|share|shares|market|markets|rbi|sebi|inflation|gdp|" +
                "ipo|bond|bonds|mutual_fund|equity|earnings|bse|nse|portfolio|tariff",
            Tags.TECH to
                "gadget|smartphone|semiconductor|chip|chips|software|developer|cybersecurity|robot|" +
                "robotics|artificial_intelligence|machine_learning|startup|cloud|firmware",
            Tags.EXPENSE to
                "bill|bills|recharge|debited|credited|upi|payment|paid|invoice|emi|subscription|" +
                "renewal|grocery|groceries|rent|utilities|wallet",
            Tags.TRAVEL to
                "travel|flight|flights|train|railway|tourism|tourist|visa|hotel|holiday|vacation|" +
                "festival|carnival|itinerary|airline|bandh|strike",
            Tags.PROMO to
                "offer|offers|discount|cashback|sale|coupon|promo_code|flat_off|freebie|" +
                "limited_period|buy_one_get_one",
            /**
             * A *nature*: something went wrong. Includes disasters, not just service
             * outages — "floods hit 49 lakh people" is an incident as much as a
             * downtime notice is, and an item can carry both `weather` (subject) and
             * `incident` (nature).
             */
            Tags.INCIDENT to
                "outage|downtime|maintenance|disrupted|disruption|restored|service_interruption|" +
                "degradation|not_working|down|flood|floods|flooding|cyclone|earthquake|landslide|" +
                "evacuated|evacuation|casualties|death_toll|relief_camp|washed_away|submerged|inundated",
            /**
             * Curated festival names. `puja`/`pujas` are bare because Indian headlines
             * pluralise them ("Durga Pujas"); the trade-off is that `Puja` is also a
             * given name, so a rare headline about a person named Puja is mistagged.
             * Region mapping comes later — see
             * docs/research/place-inference-signals.md §3.4.
             */
            Tags.FESTIVAL to
                "durga_puja|durga_pujas|puja|pujas|diwali|deepavali|pongal|onam|bihu|ganesh_chaturthi|" +
                "navratri|dussehra|dasara|holi|eid|ramadan|christmas|lohri|makara_sankranti|ugadi|" +
                "gudi_padwa|vishu|baisakhi|raksha_bandhan|janmashtami|maha_shivratri|chhath|" +
                "karva_chauth|kumbh_mela|rath_yatra",
            /**
             * Games and sport. Kept to names that are unambiguous as sports — a bare
             * `match` or `league` would fire on unrelated prose, and `f1` is written
             * both ways so both spellings are listed.
             */
            Tags.GAMES to
                "cricket|football|soccer|hockey|badminton|tennis|kabaddi|motogp|moto_gp|f1|formula_1|" +
                "formula_one|olympics|olympic|olympiad|asiad|commonwealth_games|world_cup|ipl|isl|pkl|" +
                "odi|t20|test_match|wicket|batsman|bowler|innings|grand_slam|wimbledon|premier_league|" +
                "la_liga|bundesliga|fifa|uefa|champions_league|marathon|chess|boxing|wrestling|" +
                "archery|weightlifting|athletics|medal|tournament|championship",
            Tags.PAPER to
                "arxiv|preprint|doi|peer-reviewed|journal|dataset|methodology|researchers|empirical",
            Tags.OFFICIAL to
                "circular|notification|advisory|gazette|government_order|tender|public_notice|press_release",
        )
}

/**
 * Merges term sources and compiles them into word-boundary regexes.
 *
 * Semantics: **alternatives are unioned per tag**, never replaced. Two sources that
 * both know about `games` add to each other, so a user source can extend the
 * bundled one without having to restate it.
 *
 * Compilation is memoised — a lexicon is rebuilt only when [invalidate] is called,
 * which is what a future user- or remote-sourced vocabulary would do after it
 * changes.
 */
class TermStore(
    private val sources: List<TermSource>,
) {
    private var compiled: Map<String, Regex>? = null

    fun lexicon(): Map<String, Regex> = compiled ?: build().also { compiled = it }

    fun invalidate() {
        compiled = null
    }

    private fun build(): Map<String, Regex> {
        val merged = LinkedHashMap<String, MutableList<String>>()
        for (source in sources) {
            for ((tag, alternatives) in source.terms()) {
                merged.getOrPut(tag) { mutableListOf() }.add(alternatives)
            }
        }
        return merged.mapValues { (_, parts) -> words(parts.joinToString("|")) }
    }

    private fun words(alternatives: String): Regex = Regex("""\b(?:${alternatives.replace('_', ' ')})\b""", RegexOption.IGNORE_CASE)
}
