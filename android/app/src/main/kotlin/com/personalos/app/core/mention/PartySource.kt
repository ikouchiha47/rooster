package com.personalos.app.core.mention

/**
 * One party and the names it is written under.
 *
 * @param slug stable id, also the `entity_id` stored on party mention rows.
 * @param stronghold the state name most associated with the party. A display
 *   string only — it is never matched against, so it needs no gazetteer key.
 */
data class PartyEntry(
    val slug: String,
    val name: String,
    val aliases: List<String>,
    val stronghold: String,
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

/**
 * The party vocabulary that ships with the app: national parties plus the
 * state parties an Indian feed actually names.
 *
 * Short aliases (`SP`, `NC`) are listed for documentation but never match:
 * initials need entity resolution this layer does not have yet, and matching
 * them would fire on every sentence containing those letters as words. The
 * length rule lives in [PartyLexicon], not here, so a future source cannot
 * accidentally re-enable them.
 */
object BundledPartySource : PartySource {
    override val id: String = "bundled-parties-v1"

    override fun parties(): List<PartyEntry> = PARTIES

    private val PARTIES: List<PartyEntry> =
        listOf(
            PartyEntry("bjp", "Bharatiya Janata Party", listOf("BJP", "Bharatiya Janata Party"), "Gujarat"),
            PartyEntry(
                "congress",
                "Indian National Congress",
                listOf("Congress", "Indian National Congress", "INC", "Congress Party"),
                "Karnataka",
            ),
            PartyEntry("aap", "Aam Aadmi Party", listOf("AAP", "Aam Aadmi Party"), "Delhi"),
            PartyEntry(
                "tmc",
                "All India Trinamool Congress",
                listOf("TMC", "Trinamool Congress", "All India Trinamool Congress", "Trinamool"),
                "West Bengal",
            ),
            PartyEntry("dmk", "Dravida Munnetra Kazhagam", listOf("DMK", "Dravida Munnetra Kazhagam"), "Tamil Nadu"),
            PartyEntry(
                "aiadmk",
                "All India Anna Dravida Munnetra Kazhagam",
                listOf("AIADMK", "All India Anna Dravida Munnetra Kazhagam"),
                "Tamil Nadu",
            ),
            PartyEntry(
                "cpi-m",
                "Communist Party of India (Marxist)",
                listOf("CPI(M)", "CPI-M", "Communist Party of India (Marxist)", "Communist Party of India Marxist"),
                "Kerala",
            ),
            PartyEntry("cpi", "Communist Party of India", listOf("CPI", "Communist Party of India"), "Kerala"),
            // "SP" is documented but dropped by the length rule in PartyLexicon.
            PartyEntry("sp", "Samajwadi Party", listOf("SP", "Samajwadi Party", "Samajwadi"), "Uttar Pradesh"),
            PartyEntry("bsp", "Bahujan Samaj Party", listOf("BSP", "Bahujan Samaj Party"), "Uttar Pradesh"),
            PartyEntry("rjd", "Rashtriya Janata Dal", listOf("RJD", "Rashtriya Janata Dal"), "Bihar"),
            PartyEntry(
                "jdu",
                "Janata Dal (United)",
                listOf("JD(U)", "Janata Dal (United)", "Janata Dal United"),
                "Bihar",
            ),
            PartyEntry("shiv-sena", "Shiv Sena", listOf("Shiv Sena", "Shivsena"), "Maharashtra"),
            PartyEntry("ncp", "Nationalist Congress Party", listOf("NCP", "Nationalist Congress Party"), "Maharashtra"),
            PartyEntry("bjd", "Biju Janata Dal", listOf("BJD", "Biju Janata Dal"), "Odisha"),
            PartyEntry(
                "ysrcp",
                "YSR Congress Party",
                listOf("YSRCP", "YSR Congress Party", "YSR Congress"),
                "Andhra Pradesh",
            ),
            PartyEntry(
                "tdp",
                "Telugu Desam Party",
                listOf("TDP", "Telugu Desam Party", "Telugu Desam"),
                "Andhra Pradesh",
            ),
            PartyEntry(
                "brs",
                "Bharat Rashtra Samithi",
                listOf("BRS", "Bharat Rashtra Samithi", "TRS", "Telangana Rashtra Samithi"),
                "Telangana",
            ),
            PartyEntry(
                "sad",
                "Shiromani Akali Dal",
                listOf("SAD", "Shiromani Akali Dal", "Akali Dal", "Akali"),
                "Punjab",
            ),
            // "NC" is documented but dropped by the length rule in PartyLexicon.
            PartyEntry(
                "nc",
                "Jammu and Kashmir National Conference",
                listOf("NC", "National Conference", "Jammu and Kashmir National Conference"),
                "Jammu and Kashmir",
            ),
            PartyEntry(
                "pdp",
                "Jammu and Kashmir Peoples Democratic Party",
                listOf(
                    "PDP",
                    "Peoples Democratic Party",
                    "People's Democratic Party",
                    "Jammu and Kashmir Peoples Democratic Party",
                ),
                "Jammu and Kashmir",
            ),
            PartyEntry("jmm", "Jharkhand Mukti Morcha", listOf("JMM", "Jharkhand Mukti Morcha"), "Jharkhand"),
            PartyEntry(
                "aimim",
                "All India Majlis-e-Ittehadul Muslimeen",
                listOf("AIMIM", "All India Majlis-e-Ittehadul Muslimeen", "MIM"),
                "Telangana",
            ),
            PartyEntry("agp", "Asom Gana Parishad", listOf("AGP", "Asom Gana Parishad"), "Assam"),
            PartyEntry("mnf", "Mizo National Front", listOf("MNF", "Mizo National Front"), "Mizoram"),
            PartyEntry(
                "npp",
                "National People's Party",
                listOf("NPP", "National People's Party", "National Peoples Party"),
                "Meghalaya",
            ),
            PartyEntry(
                "zpm",
                "Zoram People's Movement",
                listOf("ZPM", "Zoram People's Movement", "Zoram Peoples Movement"),
                "Mizoram",
            ),
            PartyEntry("kerala-congress", "Kerala Congress", listOf("Kerala Congress"), "Kerala"),
            PartyEntry(
                "iuml",
                "Indian Union Muslim League",
                listOf("IUML", "Indian Union Muslim League", "Muslim League"),
                "Kerala",
            ),
        )
}

/**
 * Compiled party matcher over a [PartySource]. Pure: the source supplies the
 * vocabulary, this only matches it.
 *
 * Aliases shorter than 3 characters never match — initials like `SP` or `NC`
 * need entity resolution this layer does not have, so they stay listed in the
 * source (documentation) but inert here (behaviour).
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
    }
}
