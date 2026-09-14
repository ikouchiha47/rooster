package com.personalos.app.data.remote.parties

import org.jsoup.nodes.Document

/**
 * The six list pages, one parser each. Every parser takes only
 * representation-tier tables (see [tierTables]) and maps the columns its page
 * actually carries — name always, code/stronghold where the table states them.
 * Leaders are intentionally not extracted: they are persons, not parties.
 */
object IndiaPartyParser : CountryPartyParser {
    override val country: String = "India"

    /**
     * ECI-declared national parties. Overlaid after the state-table rows so
     * `national` wins where both match the same slug. A const, not parsed:
     * the national list on the page is prose blocks rather than a table, and
     * membership changes about once a decade.
     */
    val NATIONAL_NAMES: Set<String> =
        setOf(
            "Bharatiya Janata Party",
            "Indian National Congress",
            "Aam Aadmi Party",
            "Bahujan Samaj Party",
            "Communist Party of India (Marxist)",
            "National People's Party",
        )

    override fun parse(doc: Document): List<ParsedParty> {
        val rows = ArrayList<ParsedParty>()
        for (table in tierTables(doc, headerHas = setOf("party", "recognis"))) {
            val headers = headerCells(table)
            for (row in bodyRows(table)) {
                val name = rowName(row)
                if (name.isBlank()) continue
                rows +=
                    ParsedParty(
                        englishName = name,
                        code = null,
                        recognition = "state",
                        stronghold = cellUnder(row, headers, "recognis"),
                    )
            }
        }
        // National overlay last, so it wins the merge for parties in both.
        for (name in NATIONAL_NAMES) {
            rows += ParsedParty(englishName = name, code = null, recognition = "national")
        }
        return rows
    }
}

object BrazilPartyParser : CountryPartyParser {
    override val country: String = "Brazil"

    override fun parse(doc: Document): List<ParsedParty> {
        val rows = ArrayList<ParsedParty>()
        for (table in tierTables(doc, headerHas = setOf("party", "chamber"), stopWords = setOf("without representation"))) {
            for (row in bodyRows(table)) {
                val name = rowName(row)
                if (name.isBlank()) continue
                rows += ParsedParty(englishName = name, code = rowCode(row), recognition = "congress")
            }
        }
        return rows
    }
}

object RussiaPartyParser : CountryPartyParser {
    override val country: String = "Russia"

    override fun parse(doc: Document): List<ParsedParty> {
        val rows = ArrayList<ParsedParty>()
        for (table in tierTables(doc, headerHas = setOf("party", "duma"), stopWords = setOf("historical", "soviet", "empire"))) {
            for (row in bodyRows(table)) {
                val name = rowName(row)
                if (name.isBlank()) continue
                rows += ParsedParty(englishName = name, code = rowCode(row), recognition = "duma")
            }
        }
        return rows
    }
}

object ChinaPartyParser : CountryPartyParser {
    override val country: String = "China"

    override fun parse(doc: Document): List<ParsedParty> {
        val rows = ArrayList<ParsedParty>()
        for (table in tierTables(doc, headerHas = setOf("party", "seats"), stopWords = setOf("banned", "defunct", "historical", "overseas"))) {
            val heading = nearestHeading(table)?.lowercase().orEmpty()
            // The ruling-party table sits under its own heading; everything
            // else with seats is one of the eight democratic parties.
            val recognition = if ("ruling" in heading) "ruling" else "member"
            for (row in bodyRows(table)) {
                val name = rowName(row)
                if (name.isBlank()) continue
                rows += ParsedParty(englishName = name, code = rowCode(row), recognition = recognition)
            }
        }
        return rows
    }
}

object SouthAfricaPartyParser : CountryPartyParser {
    override val country: String = "South Africa"

    override fun parse(doc: Document): List<ParsedParty> {
        val rows = ArrayList<ParsedParty>()
        // Parliamentary table only: the municipal-only and non-parliamentary
        // tables share its columns, so the heading filter does the tiering.
        for (table in tierTables(doc, headerHas = setOf("name", "abbr"), onlyHeadings = setOf("parliamentary"))) {
            for (row in bodyRows(table)) {
                val name = rowName(row)
                if (name.isBlank()) continue
                rows += ParsedParty(englishName = name, code = rowCode(row), recognition = "parliament")
            }
        }
        return rows
    }
}

object UnitedStatesPartyParser : CountryPartyParser {
    override val country: String = "United States"

    override fun parse(doc: Document): List<ParsedParty> {
        val rows = ArrayList<ParsedParty>()
        for (table in tierTables(doc, headerHas = setOf("party"))) {
            val heading = nearestHeading(table)?.lowercase().orEmpty()
            // Majors plus the state-legislature tier only; local, ballot-only
            // and no-access tails stay out.
            val recognition =
                when {
                    "major" in heading -> "major"
                    "state legislatures" in heading -> "third"
                    else -> continue
                }
            for (row in bodyRows(table)) {
                val name = rowName(row)
                if (name.isBlank()) continue
                rows += ParsedParty(englishName = name, code = rowCode(row), recognition = recognition)
            }
        }
        return rows
    }
}

/** Every parser, keyed by country — the sync walks this, not a switch. */
val COUNTRY_PARSERS: Map<String, CountryPartyParser> =
    listOf(
        IndiaPartyParser,
        BrazilPartyParser,
        RussiaPartyParser,
        ChinaPartyParser,
        SouthAfricaPartyParser,
        UnitedStatesPartyParser,
    ).associateBy { it.country }
