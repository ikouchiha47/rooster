package com.personalos.app.core.tag

/**
 * One tag's **definition** — both facets of it, in one place: how to match it
 * lexically (the heuristic rung) and how to describe it (the embedding rung).
 *
 * Declaring them together is the point. Kept apart, a new tag had to be added in
 * two maps and could silently exist for one rung and not the other — a second
 * owner of "what tags exist". Here a tag is defined once.
 *
 * [terms] is `|`-separated alternatives for rung 0; `_` is a literal space
 * (`world_cup` → "world cup"). It may be **empty** for a tag that is not matched
 * lexically (e.g. `personal`, which is sender-driven). [description] is the
 * zero-shot sentence for rung 1, and must be non-blank for anything inferrable.
 */
data class TagDefinition(
    val tag: String,
    val terms: String,
    val description: String,
)

/**
 * A source of [TagDefinition]s. **Extend tagging by adding an implementation,
 * never by editing a tagger** (docs/CODE-DESIGN-GUIDELINES.md §2): a user- or
 * remotely-sourced vocabulary is one more entry in the list, and it can add terms
 * *and* reword descriptions without a code change to either rung.
 */
interface TagVocabularySource {
    val id: String

    fun definitions(): List<TagDefinition>
}

/**
 * The vocabulary that ships with the app.
 *
 * Festivals and games are hand-curated because language alone does not derive
 * "Onam" or "MotoGP"; the sentiment is that a user-editable or remote source can
 * supply them later. `personal` carries no terms — it is assigned from the sender,
 * not the words — but it still has a description so the ML rung can consider it.
 */
object BundledVocabulary : TagVocabularySource {
    override val id: String = "bundled-v1"

    override fun definitions(): List<TagDefinition> = DEFINITIONS

    private val DEFINITIONS: List<TagDefinition> =
        listOf(
            // ------------------------------------------------------------ subjects
            TagDefinition(
                Tags.WEATHER,
                "flood|floods|flooding|cyclone|earthquake|quake|landslide|heatwave|heat_wave|" +
                    "monsoon|heavy_rain|rainfall|storm|drought|humidity|temperature|thunderstorm|cold_wave",
                "weather, rain, floods, storms, a natural disaster",
            ),
            TagDefinition(
                Tags.FINANCE,
                "sensex|nifty|rupee|stock|stocks|shares|share_price|market_share|market|markets|rbi|sebi|inflation|gdp|" +
                    "ipo|bond|bonds|mutual_fund|equity|earnings|bse|nse|portfolio|tariff",
                "finance, money, stock markets, banking, loans, interest rates",
            ),
            TagDefinition(
                Tags.TECH,
                "gadget|smartphone|semiconductor|chip|chips|software|developer|cybersecurity|robot|" +
                    "robotics|artificial_intelligence|machine_learning|startup|cloud|firmware",
                "technology, gadgets, software, artificial intelligence, cybersecurity",
            ),
            TagDefinition(
                Tags.TRAVEL,
                "travel|flight|flights|train|railway|tourism|tourist|visa|hotel|holiday|vacation|" +
                    "festival|carnival|itinerary|airline",
                "travel, flights, trains, hotels, tourism, tickets and bookings",
            ),
            // Curated festival names. `puja`/`pujas` are bare because Indian
            // headlines pluralise them ("Durga Pujas"); the trade-off is that `Puja`
            // is also a given name, so a rare headline about a person named Puja is
            // mistagged. Region mapping comes later.
            TagDefinition(
                Tags.FESTIVAL,
                "durga_puja|durga_pujas|puja|pujas|diwali|deepavali|pongal|onam|bihu|ganesh_chaturthi|" +
                    "navratri|dussehra|dasara|holi|eid|ramadan|christmas|lohri|makara_sankranti|ugadi|" +
                    "gudi_padwa|vishu|baisakhi|raksha_bandhan|janmashtami|maha_shivratri|chhath|" +
                    "karva_chauth|kumbh_mela|rath_yatra",
                "a festival, a religious holiday or a cultural celebration",
            ),
            // Kept to names that are unambiguous as sports — a bare `match` or
            // `league` would fire on unrelated prose, and `f1` is written both ways.
            TagDefinition(
                Tags.GAMES,
                "cricket|football|soccer|hockey|badminton|tennis|kabaddi|motogp|moto_gp|f1|formula_1|" +
                    "formula_one|olympics|olympic|paralympic|paralympics|olympiad|asiad|asian_games|asia_cup|" +
                    "commonwealth_games|south_asian_games|national_games|world_cup|ipl|isl|pkl|odi|t20|t20i|" +
                    "test_match|wicket|batsman|bowler|innings|ranji|champions_trophy|" +
                    "grand_slam|wimbledon|us_open|french_open|australian_open|atp|wta|davis_cup|" +
                    "thomas_cup|uber_cup|all_england|table_tennis|squash|snooker|billiards|chess|chess_olympiad|" +
                    "premier_league|la_liga|bundesliga|serie_a|i_league|fifa|uefa|champions_league|" +
                    "europa_league|copa_america|afc_asian_cup|" +
                    "archery|athletics|swimming|diving|gymnastics|sailing|rowing|canoe|fencing|judo|karate|" +
                    "taekwondo|wushu|boxing|wrestling|weightlifting|golf|rugby|rugby_sevens|basketball|" +
                    "3x3_basketball|volleyball|beach_volleyball|baseball|softball|handball|cycling|" +
                    "surfing|skateboarding|climbing|triathlon|trampoline|modern_pentathlon|equestrian|" +
                    "lacrosse|flag_football|sepaktakraw|kho_kho|" +
                    "skiing|skating|figure_skating|ice_hockey|snowboard|curling|biathlon|bobsleigh|luge|ski_jumping|" +
                    "ufc|wwe|grand_prix|marathon|diamond_league|esports|bgmi|medal|tournament|championship",
                "sports, games, matches, tournaments, cricket",
            ),
            TagDefinition(
                Tags.PAPER,
                "arxiv|preprint|doi|peer-reviewed|journal|dataset|methodology|researchers|empirical",
                "a newspaper or a publication",
            ),
            // ------------------------------------------------------------- natures
            TagDefinition(
                Tags.EXPENSE,
                "bill|bills|recharge|debited|credited|upi|payment|paid|invoice|emi|subscription|" +
                    "renewal|grocery|groceries|rent|utilities|wallet",
                "a bill, a payment, a debit or credit, a recharge, a transaction with an amount",
            ),
            TagDefinition(
                Tags.PROMO,
                "offer|offers|discount|cashback|sale|coupon|promo_code|flat_off|freebie|" +
                    "limited_period|buy_one_get_one",
                "a promotional offer, a discount, a cashback or a marketing message",
            ),
            // A *nature*: something went wrong. Includes disasters, not just service
            // outages — "floods hit 49 lakh people" is an incident as much as a
            // downtime notice is, and an item can carry both `weather` and `incident`.
            TagDefinition(
                Tags.INCIDENT,
                "outage|downtime|maintenance|disrupted|disruption|restored|service_interruption|" +
                    "degradation|not_working|down|flood|floods|flooding|cyclone|earthquake|landslide|" +
                    "evacuated|evacuation|casualties|death_toll|relief_camp|washed_away|submerged|inundated",
                "an accident, an outage, a strike, a disruption or something that went wrong",
            ),
            // No terms: `personal` is a sender fact (a phone number), not a word.
            TagDefinition(
                Tags.PERSONAL,
                terms = "",
                description = "a personal message from an individual person",
            ),
            TagDefinition(
                Tags.OFFICIAL,
                "circular|notification|advisory|gazette|government_order|tender|public_notice|press_release",
                "an official notice from a government or an organisation",
            ),
        )
}

/**
 * Merges vocabulary sources into the two read shapes, each with the right
 * semantics:
 *
 *  - **Lexicon** — alternatives are **unioned per tag**, so two sources that know
 *    `games` add to each other; a user source extends the bundled one rather than
 *    replacing it (a blank `terms` is skipped).
 *  - **Descriptions** — a later source's wording **wins**, because two sentences
 *    for one tag have no union; a source re-words, it does not append.
 *
 * Both are memoised; [invalidate] is what a future user/remote source calls after
 * it changes.
 */
class TagVocabulary(
    private val sources: List<TagVocabularySource>,
) {
    private var compiledLexicon: Map<String, Regex>? = null
    private var compiledDescriptions: Map<String, String>? = null

    fun lexicon(): Map<String, Regex> = compiledLexicon ?: buildLexicon().also { compiledLexicon = it }

    fun descriptions(): Map<String, String> = compiledDescriptions ?: buildDescriptions().also { compiledDescriptions = it }

    fun invalidate() {
        compiledLexicon = null
        compiledDescriptions = null
    }

    private fun buildLexicon(): Map<String, Regex> {
        val merged = LinkedHashMap<String, MutableList<String>>()
        for (source in sources) {
            for (definition in source.definitions()) {
                if (definition.terms.isBlank()) continue
                merged.getOrPut(definition.tag) { mutableListOf() }.add(definition.terms)
            }
        }
        return merged.mapValues { (_, parts) -> words(parts.joinToString("|")) }
    }

    private fun buildDescriptions(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (source in sources) {
            for (definition in source.definitions()) {
                if (definition.description.isNotBlank()) out[definition.tag] = definition.description
            }
        }
        return out
    }

    private fun words(alternatives: String): Regex = Regex("""\b(?:${alternatives.replace('_', ' ')})\b""", RegexOption.IGNORE_CASE)
}
