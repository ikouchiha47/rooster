package com.personalos.app.core.tag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seam that lets tagging grow without editing the tagger
 * (docs/CODE-DESIGN-GUIDELINES.md §2).
 */
class TermStoreTest {
    private fun source(
        id: String,
        vararg pairs: Pair<String, String>,
    ) = object : TermSource {
        override val id = id

        override fun terms(): Map<String, String> = pairs.toMap()

        override fun toString() = id
    }

    @Test
    fun `alternatives from several sources are unioned, not replaced`() {
        val bundled = source("bundled", Tags.GAMES to "cricket")
        val user = source("user", Tags.GAMES to "kabaddi")

        val games = TermStore(listOf(bundled, user)).lexicon().getValue(Tags.GAMES)

        assertTrue("the first source survives", games.containsMatchIn("a cricket match"))
        assertTrue("the second source is added", games.containsMatchIn("kabaddi league"))
    }

    @Test
    fun `a source cannot erase an earlier one`() {
        // Two sources defining the same tag must add to each other - otherwise a
        // user-supplied vocabulary would silently delete the bundled one.
        val first = source("a", Tags.FESTIVAL to "onam")
        val second = source("b", Tags.FESTIVAL to "bihu")

        val festival = TermStore(listOf(first, second)).lexicon().getValue(Tags.FESTIVAL)

        assertTrue(festival.containsMatchIn("Onam"))
        assertTrue(festival.containsMatchIn("Bihu"))
    }

    @Test
    fun `the lexicon is compiled once and rebuilt only on invalidate`() {
        var reads = 0
        val counting =
            object : TermSource {
                override val id = "counting"

                override fun terms(): Map<String, String> {
                    reads++
                    return mapOf(Tags.GAMES to "ipl")
                }
            }

        val store = TermStore(listOf(counting))
        store.lexicon()
        store.lexicon()
        assertEquals("second call must be cached", 1, reads)

        store.invalidate()
        store.lexicon()
        assertEquals("invalidate forces a rebuild", 2, reads)
    }

    @Test
    fun `underscores become spaces so multi-word terms match as phrases`() {
        val terms = source("multi", Tags.GAMES to "world_cup")

        val games = TermStore(listOf(terms)).lexicon().getValue(Tags.GAMES)

        assertTrue(games.containsMatchIn("the world cup final"))
        assertFalse("the phrase must not match when unspaced", games.containsMatchIn("worldcup"))
    }

    @Test
    fun `terms match on word boundaries, not substrings`() {
        val terms = source("bounded", Tags.GAMES to "f1")

        val games = TermStore(listOf(terms)).lexicon().getValue(Tags.GAMES)

        assertFalse("'f1' must not fire inside another token", games.containsMatchIn("nf1x"))
    }

    @Test
    fun `the bundled source carries every curated vocabulary`() {
        val bundled = BundledTermSource.terms().keys
        assertTrue(
            "festival and games are the hand-curated ones",
            bundled.containsAll(listOf(Tags.FESTIVAL, Tags.GAMES)),
        )
        assertTrue(
            "and the inferred subjects are still there",
            bundled.containsAll(listOf(Tags.WEATHER, Tags.FINANCE, Tags.TECH, Tags.TRAVEL)),
        )
    }
}
