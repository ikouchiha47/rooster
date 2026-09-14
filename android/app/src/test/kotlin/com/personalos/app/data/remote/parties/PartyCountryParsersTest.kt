package com.personalos.app.data.remote.parties

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture tests: small but structurally faithful tables proving each parser
 * takes its representation tier and skips the tails. The shared failure this
 * guards is same-column decoy tables (Brazil's unrepresented tier, China's
 * banned table, SA's municipal table, US local tails) — tiering comes from
 * headings, never from wishful column-matching.
 */
class PartyCountryParsersTest {
    @Test
    fun `india takes the recognised-state table and overlays the national six`() {
        val doc =
            Jsoup.parse(
                """
                <h2><span class="mw-headline" id="Recognised_state_parties">x</span></h2>
                <table class="wikitable"><tr><th>Party</th><th>Flag</th><th>Recognised in state(s)</th><th>Lok Sabha</th></tr>
                <tr><td><b>TMC</b> All India Trinamool Congress</td><td></td><td>West Bengal</td><td>29 / 543</td></tr>
                <tr><td>Bharatiya Janata Party</td><td></td><td>Gujarat</td><td>240 / 543</td></tr>
                </table>
                <h2><span class="mw-headline" id="Historical_parties">x</span></h2>
                <table class="wikitable"><tr><th>Party</th><th>Founded</th></tr>
                <tr><td>Janata Party</td><td>1977</td></tr></table>
                """.trimIndent(),
            )

        val rows = IndiaPartyParser.parse(doc)
        val byName = rows.associateBy { it.englishName }

        // "TMC All India Trinamool Congress": first-cell text, refs stripped.
        assertTrue(byName.keys.any { "Trinamool" in it })
        val tmc = rows.first { "Trinamool" in it.englishName }
        assertEquals("West Bengal", tmc.stronghold)
        assertEquals("state", tmc.recognition)
        // The historical table lacks a Recognised column, so it is out. Exact
        // match, because "Bharatiya Janata Party" contains "Janata Party".
        assertTrue(rows.none { it.englishName == "Janata Party" })
        // National overlay: BJP is national despite its state-table row.
        assertEquals("national", byName.getValue("Bharatiya Janata Party").recognition)
    }

    @Test
    fun `brazil skips the unrepresented tier despite identical columns`() {
        val doc =
            Jsoup.parse(
                """
                <h2><span class="mw-headline" id="a">Parties with representation in the National Congress</span></h2>
                <table class="wikitable"><tr><th>Party</th><th>Chamber</th></tr>
                <tr><td><b>PL</b> Liberal Party</td><td>90 / 513</td></tr></table>
                <h2><span class="mw-headline" id="b">Parties without representation in the National Congress</span></h2>
                <table class="wikitable"><tr><th>Party</th><th>Chamber</th></tr>
                <tr><td><b>PCO</b> Workers' Cause Party</td><td>0 / 513</td></tr></table>
                """.trimIndent(),
            )

        val rows = BrazilPartyParser.parse(doc)
        assertEquals(listOf("congress"), rows.map { it.recognition }.distinct())
        assertTrue(rows.any { "Liberal Party" in it.englishName })
        assertTrue("tail party excluded", rows.none { "Cause" in it.englishName })
    }

    @Test
    fun `russia takes the duma table only`() {
        val doc =
            Jsoup.parse(
                """
                <h2><span class="mw-headline" id="a">Parties represented in the Federal Assembly</span></h2>
                <table class="wikitable"><tr><th>Party</th><th>State Duma</th></tr>
                <tr><td><b>ER</b> United Russia</td><td>325 / 450</td></tr></table>
                <h2><span class="mw-headline" id="b">Parties represented in the regional parliaments</span></h2>
                <table class="wikitable"><tr><th>Name</th><th>Regional parliaments</th></tr>
                <tr><td>Yabloko</td><td>11 / 3908</td></tr></table>
                """.trimIndent(),
            )

        val rows = RussiaPartyParser.parse(doc)
        assertEquals(1, rows.size)
        assertEquals("duma", rows[0].recognition)
        assertEquals("ER", rows[0].code)
    }

    @Test
    fun `china separates the ruling party from the eight and skips the banned`() {
        val doc =
            Jsoup.parse(
                """
                <h3><span class="mw-headline" id="a">Ruling party</span></h3>
                <table class="wikitable"><tr><th>Party</th><th>NPC seats</th></tr>
                <tr><td>Chinese Communist Party</td><td>2091 / 2980</td></tr></table>
                <h3><span class="mw-headline" id="b">Minor parties</span></h3>
                <table class="wikitable"><tr><th>Party</th><th>NPC seats</th></tr>
                <tr><td>China Democratic League</td><td>58 / 2980</td></tr></table>
                <h3><span class="mw-headline" id="c">Banned parties</span></h3>
                <table class="wikitable"><tr><th>Party</th><th>NPC seats</th></tr>
                <tr><td>Democracy Party of China</td><td>0</td></tr></table>
                """.trimIndent(),
            )

        val byName = ChinaPartyParser.parse(doc).associateBy { it.englishName }
        assertEquals("ruling", byName.getValue("Chinese Communist Party").recognition)
        assertEquals("member", byName.getValue("China Democratic League").recognition)
        assertTrue("banned party excluded", "Democracy Party of China" !in byName)
    }

    @Test
    fun `south africa takes the parliamentary table despite shared columns`() {
        val doc =
            Jsoup.parse(
                """
                <h2><span class="mw-headline" id="a">Parliamentary parties</span></h2>
                <table class="wikitable"><tr><th>Name</th><th>Abbr.</th><th>National Assembly</th></tr>
                <tr><td>African National Congress</td><td><b>ANC</b></td><td>159 / 400</td></tr></table>
                <h2><span class="mw-headline" id="b">Other parties with representation</span></h2>
                <table class="wikitable"><tr><th>Name</th><th>Abbr.</th><th>Municipal council seats</th></tr>
                <tr><td>National Freedom Party</td><td><b>NFP</b></td><td>52 / 8794</td></tr></table>
                """.trimIndent(),
            )

        val rows = SouthAfricaPartyParser.parse(doc)
        assertEquals(1, rows.size)
        assertEquals("ANC", rows[0].code)
        assertEquals("parliament", rows[0].recognition)
    }

    @Test
    fun `united states takes majors and state-represented thirds, skips local tails`() {
        val doc =
            Jsoup.parse(
                """
                <h3><span class="mw-headline" id="a">Major parties</span></h3>
                <table class="wikitable"><tr><th>Party</th><th>Senate</th></tr>
                <tr><td>Republican Party (R; GOP)</td><td>53 / 100</td></tr></table>
                <h4><span class="mw-headline" id="b">Represented in state legislatures</span></h4>
                <table class="wikitable"><tr><th>Party</th><th>State legislators</th></tr>
                <tr><td>Working Families Party (WFP)</td><td>159 / 7383</td></tr></table>
                <h4><span class="mw-headline" id="c">Represented in local governments</span></h4>
                <table class="wikitable"><tr><th>Party</th><th>Local electeds</th></tr>
                <tr><td>Green Party (G; GRE)</td><td>46 / 188409</td></tr></table>
                """.trimIndent(),
            )

        val byName = UnitedStatesPartyParser.parse(doc).associateBy { it.englishName }
        assertEquals("major", byName.getValue("Republican Party (R; GOP)").recognition)
        assertEquals("third", byName.getValue("Working Families Party (WFP)").recognition)
        assertTrue("local tail excluded", byName.keys.none { "Green" in it })
    }

    @Test
    fun `slugish folds codes and names to stable ids`() {
        assertEquals("uniao", slugish("UNIÃO"))
        assertEquals("kprf", slugish("KPRF"))
        assertEquals("cpi-m", slugish("CPI(M)"))
        assertEquals("communist-party-of-india-marxist", slugish("Communist Party of India (Marxist)"))
        assertEquals("a-just-russia", slugish("A Just Russia"))
    }
}
