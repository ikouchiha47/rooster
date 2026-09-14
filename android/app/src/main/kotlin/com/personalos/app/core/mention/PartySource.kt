package com.personalos.app.core.mention

/**
 * One party and the names it is written under.
 *
 * @param slug stable id, also the `entity_id` stored on party mention rows.
 *   Lowercased English name, parenthetical qualifiers stripped, non-alphanumeric
 *   runs folded to a single hyphen (`Communist Party of India (Marxist)` →
 *   `cpi-m`).
 * @param country the country this party belongs to. The location signal for
 *   national parties; state-level precision comes from [stronghold] where known.
 * @param stronghold the state name most associated with the party. A display
 *   string only — it is never matched against, so it needs no gazetteer key.
 *   Empty when the source states none; a missing stronghold is honest, a
 *   guessed one is a wrong location.
 * @param recognition the tier that earned it a row: `national` / `state`
 *   (India, ECI tiers), `congress` (Brazil), `duma` (Russia), `ruling` /
 *   `member` (China), `parliament` (South Africa), `major` / `third` (US).
 *   Prominence ranking only, never matched.
 */
data class PartyEntry(
    val slug: String,
    val country: String,
    val name: String,
    val aliases: List<String>,
    val stronghold: String,
    val recognition: String,
)

/**
 * A source of party vocabulary.
 *
 * **Extend the party list by adding an implementation, never by editing
 * [PartyLexicon]** — the same split as [com.personalos.app.core.tag.TermSource]
 * and its bundled source: vocabulary is data, matching is code.
 */
interface PartySource {
    val id: String

    fun parties(): List<PartyEntry>
}

/** A [PartySource] over an explicit list — what the synced table loads into. */
data class ListPartySource(
    override val id: String,
    private val entries: List<PartyEntry>,
) : PartySource {
    override fun parties(): List<PartyEntry> = entries
}

/**
 * The list pages the party registry syncs from, one per country. Kept in code
 * (not the database) so the sync worker and any future importer share them
 * without a query, and a changed URL is a one-line diff.
 *
 * All CC BY-SA; attribution lives with the other dataset credits.
 */
object PartySourceUrls {
    const val INDIA = "https://en.wikipedia.org/wiki/List_of_political_parties_in_India"
    const val BRAZIL = "https://en.wikipedia.org/wiki/List_of_political_parties_in_Brazil"
    const val RUSSIA = "https://en.wikipedia.org/wiki/List_of_political_parties_in_Russia"
    const val CHINA = "https://en.wikipedia.org/wiki/List_of_political_parties_in_China"
    const val SOUTH_AFRICA = "https://en.wikipedia.org/wiki/List_of_political_parties_in_South_Africa"
    const val UNITED_STATES = "https://en.wikipedia.org/wiki/List_of_political_parties_in_the_United_States"

    val ALL: Map<String, String> =
        mapOf(
            "India" to INDIA,
            "Brazil" to BRAZIL,
            "Russia" to RUSSIA,
            "China" to CHINA,
            "South Africa" to SOUTH_AFRICA,
            "United States" to UNITED_STATES,
        )
}

/**
 * The party vocabulary that ships with the app, seeding the `parties` table on
 * first launch (the sync keeps it afterwards).
 *
 * Selection rule, uniform across countries: the **representation tier only**.
 * National + recognised-state parties (India), Congress-represented (Brazil),
 * Duma parties (Russia), the ruling party + the eight democratic parties
 * (China), parliamentary parties (South Africa), majors + seated third parties
 * (US). Tails — municipal-only, single-state, no-ballot-access — are skipped;
 * a tail party that matters graduates via the sync or a user addition.
 *
 * Alias rules learned going worldwide (each documented where it bites):
 *  - bare `Congress` is gone: in world news it means the US legislature, not
 *    the Indian party. `Congress Party` and `INC` stay.
 *  - two-letter codes (`PL`, `PT`, `DA`, `SP`, `NC`) are inert via the length
 *    rule in [PartyLexicon]; initials need entity resolution we do not have.
 *  - bare generic words stay off even when they are real names: `Good` (a
 *    common adjective) is stopworded in [PartyLexicon]; `ATM`, `Solidarity`
 *    and `Forward` match on full names only.
 *  - bare generic names go to the highest-volume country: `Green Party` is the
 *    US Greens; Brazil's is `Green Party of Brazil`. First match wins in
 *    [PartyLexicon], and the list below is ordered India, US, Brazil, Russia,
 *    China, South Africa on that basis.
 */
object BundledPartySource : PartySource {
    override val id: String = "bundled-parties-v2"

    override fun parties(): List<PartyEntry> = PARTIES

    private val PARTIES: List<PartyEntry> =
        listOf(
            // ---------------------------------------------------------- India
            PartyEntry("bjp", "India", "Bharatiya Janata Party", listOf("BJP", "Bharatiya Janata Party"), "Gujarat", "national"),
            // Bare "Congress" deliberately absent: worldwide it reads as the US
            // legislature. "Congress Party" is unambiguous and stays.
            PartyEntry(
                "congress",
                "India",
                "Indian National Congress",
                listOf("Indian National Congress", "INC", "Congress Party"),
                "Karnataka",
                "national",
            ),
            PartyEntry("aap", "India", "Aam Aadmi Party", listOf("AAP", "Aam Aadmi Party"), "Delhi", "national"),
            PartyEntry(
                "tmc",
                "India",
                "All India Trinamool Congress",
                listOf("TMC", "Trinamool Congress", "All India Trinamool Congress", "Trinamool"),
                "West Bengal",
                "state",
            ),
            PartyEntry("dmk", "India", "Dravida Munnetra Kazhagam", listOf("DMK", "Dravida Munnetra Kazhagam"), "Tamil Nadu", "state"),
            PartyEntry(
                "aiadmk",
                "India",
                "All India Anna Dravida Munnetra Kazhagam",
                listOf("AIADMK", "All India Anna Dravida Munnetra Kazhagam"),
                "Tamil Nadu",
                "state",
            ),
            PartyEntry(
                "cpi-m",
                "India",
                "Communist Party of India (Marxist)",
                listOf("CPI(M)", "CPI-M", "Communist Party of India (Marxist)", "Communist Party of India Marxist"),
                "Kerala",
                "national",
            ),
            PartyEntry("cpi", "India", "Communist Party of India", listOf("CPI", "Communist Party of India"), "Kerala", "state"),
            // "SP" is documented but dropped by the length rule in PartyLexicon.
            PartyEntry("sp", "India", "Samajwadi Party", listOf("SP", "Samajwadi Party", "Samajwadi"), "Uttar Pradesh", "state"),
            PartyEntry("bsp", "India", "Bahujan Samaj Party", listOf("BSP", "Bahujan Samaj Party"), "Uttar Pradesh", "national"),
            PartyEntry("rjd", "India", "Rashtriya Janata Dal", listOf("RJD", "Rashtriya Janata Dal"), "Bihar", "state"),
            PartyEntry(
                "jdu",
                "India",
                "Janata Dal (United)",
                listOf("JD(U)", "Janata Dal (United)", "Janata Dal United"),
                "Bihar",
                "state",
            ),
            PartyEntry("shiv-sena", "India", "Shiv Sena", listOf("Shiv Sena", "Shivsena"), "Maharashtra", "state"),
            PartyEntry("ncp", "India", "Nationalist Congress Party", listOf("NCP", "Nationalist Congress Party"), "Maharashtra", "state"),
            PartyEntry("bjd", "India", "Biju Janata Dal", listOf("BJD", "Biju Janata Dal"), "Odisha", "state"),
            PartyEntry(
                "ysrcp",
                "India",
                "YSR Congress Party",
                listOf("YSRCP", "YSR Congress Party", "YSR Congress"),
                "Andhra Pradesh",
                "state",
            ),
            PartyEntry(
                "tdp",
                "India",
                "Telugu Desam Party",
                listOf("TDP", "Telugu Desam Party", "Telugu Desam"),
                "Andhra Pradesh",
                "state",
            ),
            PartyEntry(
                "brs",
                "India",
                "Bharat Rashtra Samithi",
                listOf("BRS", "Bharat Rashtra Samithi", "TRS", "Telangana Rashtra Samithi"),
                "Telangana",
                "state",
            ),
            PartyEntry(
                "sad",
                "India",
                "Shiromani Akali Dal",
                listOf("SAD", "Shiromani Akali Dal", "Akali Dal", "Akali"),
                "Punjab",
                "state",
            ),
            // "NC" is documented but dropped by the length rule in PartyLexicon.
            PartyEntry(
                "nc",
                "India",
                "Jammu and Kashmir National Conference",
                listOf("NC", "National Conference", "Jammu and Kashmir National Conference"),
                "Jammu and Kashmir",
                "state",
            ),
            PartyEntry(
                "pdp",
                "India",
                "Jammu and Kashmir Peoples Democratic Party",
                listOf(
                    "PDP",
                    "Peoples Democratic Party",
                    "People's Democratic Party",
                    "Jammu and Kashmir Peoples Democratic Party",
                ),
                "Jammu and Kashmir",
                "state",
            ),
            PartyEntry("jmm", "India", "Jharkhand Mukti Morcha", listOf("JMM", "Jharkhand Mukti Morcha"), "Jharkhand", "state"),
            PartyEntry(
                "aimim",
                "India",
                "All India Majlis-e-Ittehadul Muslimeen",
                listOf("AIMIM", "All India Majlis-e-Ittehadul Muslimeen", "MIM"),
                "Telangana",
                "state",
            ),
            PartyEntry("agp", "India", "Asom Gana Parishad", listOf("AGP", "Asom Gana Parishad"), "Assam", "state"),
            PartyEntry("mnf", "India", "Mizo National Front", listOf("MNF", "Mizo National Front"), "Mizoram", "state"),
            PartyEntry(
                "npp",
                "India",
                "National People's Party",
                listOf("NPP", "National People's Party", "National Peoples Party"),
                "Meghalaya",
                "national",
            ),
            PartyEntry(
                "zpm",
                "India",
                "Zoram People's Movement",
                listOf("ZPM", "Zoram People's Movement", "Zoram Peoples Movement"),
                "Mizoram",
                "state",
            ),
            PartyEntry("kerala-congress", "India", "Kerala Congress", listOf("Kerala Congress"), "Kerala", "state"),
            PartyEntry(
                "iuml",
                "India",
                "Indian Union Muslim League",
                listOf("IUML", "Indian Union Muslim League", "Muslim League"),
                "Kerala",
                "state",
            ),
            // ------------------------------------------------- United States
            PartyEntry(
                "republican",
                "United States",
                "Republican Party",
                listOf("Republican Party", "Republicans", "GOP"),
                "",
                "major",
            ),
            PartyEntry(
                "democrat",
                "United States",
                "Democratic Party",
                listOf("Democratic Party", "Democrats", "DEM"),
                "",
                "major",
            ),
            // Bare "Green Party" lives here (highest news volume); Brazil's is
            // qualified. First match wins in PartyLexicon.
            PartyEntry("green-party", "United States", "Green Party", listOf("Green Party", "Greens", "GRE"), "", "third"),
            PartyEntry(
                "libertarian-party",
                "United States",
                "Libertarian Party",
                listOf("Libertarian Party", "Libertarians", "LIB"),
                "",
                "third",
            ),
            PartyEntry("constitution-party", "United States", "Constitution Party", listOf("Constitution Party"), "", "third"),
            PartyEntry("working-families-party", "United States", "Working Families Party", listOf("Working Families Party", "WFP"), "", "third"),
            // "Forward Party" is distinct from Brazil's bare "Forward" (Avante);
            // longest-match arbitration keeps them apart.
            PartyEntry("forward-party", "United States", "Forward Party", listOf("Forward Party", "FWD"), "", "third"),
            PartyEntry(
                "vermont-progressive-party",
                "United States",
                "Vermont Progressive Party",
                listOf("Vermont Progressive Party", "VPP"),
                "Vermont",
                "third",
            ),
            // ---------------------------------------------------------- Brazil
            // Two-letter codes (PL, PT, PP, PV) are inert via the length rule;
            // full names and longer codes carry these rows.
            PartyEntry("pl", "Brazil", "Liberal Party", listOf("Liberal Party", "Partido Liberal"), "", "congress"),
            PartyEntry("pt", "Brazil", "Workers' Party", listOf("Workers' Party", "Partido dos Trabalhadores", "Workers Party"), "", "congress"),
            PartyEntry("uniao", "Brazil", "Brazil Union", listOf("UNIÃO", "UNIAO", "Brazil Union", "União Brasil"), "", "congress"),
            PartyEntry("pp", "Brazil", "Progressistas", listOf("Progressistas", "Progressives"), "", "congress"),
            PartyEntry(
                "psd",
                "Brazil",
                "Social Democratic Party",
                listOf("PSD", "Social Democratic Party", "Partido Social Democrático"),
                "",
                "congress",
            ),
            PartyEntry("republicanos", "Brazil", "Republicans", listOf("Republicanos"), "", "congress"),
            PartyEntry("mdb", "Brazil", "Brazilian Democratic Movement", listOf("MDB", "Brazilian Democratic Movement"), "", "congress"),
            PartyEntry("psb", "Brazil", "Brazilian Socialist Party", listOf("PSB", "Brazilian Socialist Party"), "", "congress"),
            PartyEntry("podemos", "Brazil", "Podemos", listOf("PODE", "Podemos", "We Can"), "", "congress"),
            PartyEntry("psdb", "Brazil", "Brazilian Social Democracy Party", listOf("PSDB", "Brazilian Social Democracy Party"), "", "congress"),
            PartyEntry("pdt", "Brazil", "Democratic Labour Party", listOf("PDT", "Democratic Labour Party"), "", "congress"),
            PartyEntry("psol", "Brazil", "Socialism and Liberty Party", listOf("PSOL", "Socialism and Liberty Party"), "", "congress"),
            PartyEntry("pcdob", "Brazil", "Communist Party of Brazil", listOf("PCdoB", "Communist Party of Brazil"), "", "congress"),
            // Bare "Forward" only: the US "Forward Party" is a longer, distinct
            // surface and wins where both could match.
            PartyEntry("avante", "Brazil", "Avante", listOf("Avante", "Forward"), "", "congress"),
            PartyEntry("novo", "Brazil", "New Party", listOf("NOVO", "New Party", "Partido Novo"), "", "congress"),
            // Bare "Solidarity" stays off: it is an ordinary English noun first.
            PartyEntry("solidariedade", "Brazil", "Solidariedade", listOf("Solidariedade"), "", "congress"),
            PartyEntry("cidadania", "Brazil", "Cidadania", listOf("Cidadania"), "", "congress"),
            PartyEntry("pv", "Brazil", "Green Party of Brazil", listOf("PV", "Green Party of Brazil"), "", "congress"),
            PartyEntry("rede", "Brazil", "Sustainability Network", listOf("REDE", "Sustainability Network", "Rede"), "", "congress"),
            PartyEntry("prd", "Brazil", "Democratic Renewal Party", listOf("PRD", "Democratic Renewal Party"), "", "congress"),
            PartyEntry("missao", "Brazil", "Mission Party", listOf("MISSÃO", "MISSAO", "Mission Party"), "", "congress"),
            // ---------------------------------------------------------- Russia
            // Two-letter codes (ER, SR) are inert via the length rule.
            PartyEntry("united-russia", "Russia", "United Russia", listOf("United Russia", "Единая Россия"), "", "duma"),
            PartyEntry(
                "kprf",
                "Russia",
                "Communist Party of the Russian Federation",
                listOf("KPRF", "Communist Party of the Russian Federation", "КПРФ"),
                "",
                "duma",
            ),
            PartyEntry("sr", "Russia", "A Just Russia", listOf("A Just Russia", "Справедливая Россия"), "", "duma"),
            PartyEntry(
                "ldpr",
                "Russia",
                "Liberal Democratic Party of Russia",
                listOf("LDPR", "Liberal Democratic Party of Russia", "ЛДПР"),
                "",
                "duma",
            ),
            PartyEntry("new-people", "Russia", "New People", listOf("New People", "Новые люди"), "", "duma"),
            PartyEntry("rodina", "Russia", "Rodina", listOf("Rodina", "Родина"), "", "duma"),
            PartyEntry("civic-platform", "Russia", "Civic Platform", listOf("Civic Platform", "Гражданская платформа"), "", "duma"),
            // ----------------------------------------------------------- China
            PartyEntry(
                "cpc",
                "China",
                "Chinese Communist Party",
                listOf("CPC", "CCP", "Chinese Communist Party", "Communist Party of China", "中国共产党"),
                "",
                "ruling",
            ),
            PartyEntry(
                "rcck",
                "China",
                "Revolutionary Committee of the Chinese Kuomintang",
                listOf("RCCK", "Revolutionary Committee of the Chinese Kuomintang"),
                "",
                "member",
            ),
            PartyEntry("cdl", "China", "China Democratic League", listOf("CDL", "China Democratic League"), "", "member"),
            PartyEntry(
                "cndca",
                "China",
                "China National Democratic Construction Association",
                listOf("CNDCA", "China National Democratic Construction Association"),
                "",
                "member",
            ),
            PartyEntry(
                "capd",
                "China",
                "China Association for Promoting Democracy",
                listOf("CAPD", "China Association for Promoting Democracy"),
                "",
                "member",
            ),
            PartyEntry(
                "cpwdp",
                "China",
                "Chinese Peasants' and Workers' Democratic Party",
                listOf("CPWDP", "Chinese Peasants' and Workers' Democratic Party"),
                "",
                "member",
            ),
            PartyEntry("czgp", "China", "China Zhi Gong Party", listOf("CZGP", "China Zhi Gong Party"), "", "member"),
            PartyEntry("js", "China", "Jiusan Society", listOf("Jiusan Society"), "", "member"),
            PartyEntry(
                "tdsl",
                "China",
                "Taiwan Democratic Self-Government League",
                listOf("TDSL", "Taiwan Democratic Self-Government League"),
                "",
                "member",
            ),
            // ---------------------------------------------------- South Africa
            PartyEntry("anc", "South Africa", "African National Congress", listOf("ANC", "African National Congress"), "", "parliament"),
            PartyEntry(
                "da",
                "South Africa",
                "Democratic Alliance",
                listOf("Democratic Alliance"),
                "Western Cape",
                "parliament",
            ),
            PartyEntry("mk", "South Africa", "uMkhonto weSizwe Party", listOf("uMkhonto weSizwe", "MK Party"), "KwaZulu-Natal", "parliament"),
            PartyEntry("eff", "South Africa", "Economic Freedom Fighters", listOf("EFF", "Economic Freedom Fighters"), "", "parliament"),
            PartyEntry("ifp", "South Africa", "Inkatha Freedom Party", listOf("IFP", "Inkatha Freedom Party"), "KwaZulu-Natal", "parliament"),
            // "PA" is two letters: inert via the length rule, full name carries it.
            PartyEntry("pa", "South Africa", "Patriotic Alliance", listOf("Patriotic Alliance"), "", "parliament"),
            // "VF"/"VF+" unusable (length rule; the + breaks word bounds), so the
            // full name is the only surface.
            PartyEntry("freedom-front-plus", "South Africa", "Freedom Front Plus", listOf("Freedom Front Plus"), "", "parliament"),
            PartyEntry("actionsa", "South Africa", "ActionSA", listOf("ActionSA"), "", "parliament"),
            PartyEntry(
                "acdp",
                "South Africa",
                "African Christian Democratic Party",
                listOf("ACDP", "African Christian Democratic Party"),
                "",
                "parliament",
            ),
            PartyEntry("udm", "South Africa", "United Democratic Movement", listOf("UDM", "United Democratic Movement"), "", "parliament"),
            PartyEntry("rise", "South Africa", "Rise Mzansi", listOf("RISE", "Rise Mzansi"), "", "parliament"),
            PartyEntry("bosa", "South Africa", "Build One South Africa", listOf("BOSA", "Build One South Africa"), "", "parliament"),
            // Bare "ATM" stays off: in finance-heavy feeds it means cash machine.
            PartyEntry("atm", "South Africa", "African Transformation Movement", listOf("African Transformation Movement"), "", "parliament"),
            PartyEntry("aljama", "South Africa", "Al Jama-ah", listOf("ALJAMA", "Al Jama-ah"), "", "parliament"),
            PartyEntry("ncc", "South Africa", "National Coloured Congress", listOf("NCC", "National Coloured Congress"), "", "parliament"),
            PartyEntry(
                "pac",
                "South Africa",
                "Pan Africanist Congress of Azania",
                listOf("PAC", "Pan Africanist Congress of Azania"),
                "",
                "parliament",
            ),
            PartyEntry("uat", "South Africa", "United Africans Transformation", listOf("UAT", "United Africans Transformation"), "", "parliament"),
            // Bare "Good" is an ordinary adjective: stopworded in PartyLexicon,
            // so this row is registry-only until a safer surface exists.
            PartyEntry("good", "South Africa", "Good", listOf("Good"), "", "parliament"),
        )
}

/**
 * Compiled party matcher over a [PartySource]. Pure: the source supplies the
 * vocabulary, this only matches it.
 *
 * Aliases shorter than 3 characters never match — initials like `SP` or `NC`
 * need entity resolution this layer does not have, so they stay listed in the
 * source (documentation) but inert here (behaviour).
 *
 * [STOPWORDS] holds the opposite case: surfaces long enough to match that are
 * ordinary words first (`Good`). Registry rows, never matches.
 */
class PartyLexicon(
    source: PartySource = BundledPartySource,
) {
    /** A raw span hit, before cross-kind arbitration. */
    data class Hit(
        val surface: String,
        val entry: PartyEntry,
        val start: Int,
        val end: Int,
    )

    private val byLower: Map<String, Entry>
    private val pattern: Regex?

    init {
        val merged = LinkedHashMap<String, Entry>()
        for (party in source.parties()) {
            for (alias in party.aliases) {
                val trimmed = alias.trim()
                if (trimmed.length < MIN_ALIAS_LENGTH) continue
                if (trimmed.lowercase() in STOPWORDS) continue
                merged.putIfAbsent(trimmed.lowercase(), Entry(trimmed, party))
            }
        }
        byLower = merged
        pattern =
            wordPattern(
                merged.keys.sortedByDescending { it.length }.map { Regex.escape(it) },
            )
    }

    /** Every party span in [text], longest match winning at each position. */
    fun find(text: String): List<Hit> {
        val regex = pattern ?: return emptyList()
        return regex
            .findAll(text)
            .mapNotNull { match ->
                val entry = byLower[match.value.lowercase()] ?: return@mapNotNull null
                Hit(
                    surface = entry.surface,
                    entry = entry.party,
                    start = match.range.first,
                    end = match.range.last + 1,
                )
            }.toList()
    }

    private data class Entry(
        val surface: String,
        val party: PartyEntry,
    )

    companion object {
        const val MIN_ALIAS_LENGTH = 3

        /** Ordinary words that happen to be party names. Registry, not matches. */
        val STOPWORDS: Set<String> = setOf("good")
    }
}
