package com.personalos.app.core.mention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The worldwide consequences, pinned: bare `Congress` is gone (it means the US
 * legislature now), `Good` never matches despite being a real party, short
 * codes stay inert, and the new countries resolve through the same lexicon.
 */
class PartyLexiconWorldwideTest {
    private val lexicon = PartyLexicon(BundledPartySource)

    private fun slugs(text: String): List<String> = lexicon.find(text).map { it.entry.slug }

    @Test
    fun `the new countries resolve`() {
        assertEquals(listOf("republican"), slugs("GOP wins the House"))
        assertEquals(listOf("anc"), slugs("ANC rally in Soweto"))
        assertEquals(listOf("cpc"), slugs("CPC congress in Beijing"))
        assertEquals(listOf("pt"), slugs("Lula's Workers' Party backs the bill"))
    }

    @Test
    fun `india still resolves after the worldwide rewrite`() {
        assertEquals(listOf("bjp"), slugs("BJP rally in Gujarat"))
        assertEquals(listOf("tmc"), slugs("TMC sweeps Bengal"))
        assertEquals(listOf("dmk"), slugs("DMK leader Stalin"))
    }

    @Test
    fun `bare congress no longer claims the us legislature`() {
        assertTrue(
            "Congress approved the bill must not match any party",
            slugs("Congress approved the bill").isEmpty(),
        )
        assertEquals(listOf("congress"), slugs("Congress Party wins Karnataka"))
    }

    @Test
    fun `good morning is not a party`() {
        assertTrue(slugs("Good morning, markets rally").isEmpty())
    }

    @Test
    fun `atm means cash machine, not a party`() {
        assertTrue(slugs("ATM withdrawal limit raised").isEmpty())
        assertEquals(listOf("atm"), slugs("African Transformation Movement marches"))
    }

    @Test
    fun `forward and forward party stay apart`() {
        assertEquals(listOf("forward-party"), slugs("Forward Party convention"))
        assertEquals(listOf("avante"), slugs("Avante bloc votes"))
    }
}
