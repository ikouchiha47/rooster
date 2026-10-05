package com.personalos.app.core.tag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seam that lets tagging grow without editing a tagger
 * (docs/CODE-DESIGN-GUIDELINES.md §2), and the one owner of "what tags exist":
 * a tag is declared once, with both its terms and its description.
 */
class TagVocabularyTest {
    private fun source(
        id: String,
        vararg definitions: TagDefinition,
    ) = object : TagVocabularySource {
        override val id = id

        override fun definitions(): List<TagDefinition> = definitions.toList()

        override fun toString() = id
    }

    private fun def(
        tag: String,
        terms: String = "",
        description: String = "a description",
    ) = TagDefinition(tag, terms, description)

    @Test
    fun `terms from several sources are unioned, not replaced`() {
        val bundled = source("bundled", def(Tags.GAMES, "cricket"))
        val user = source("user", def(Tags.GAMES, "kabaddi"))

        val games = TagVocabulary(listOf(bundled, user)).lexicon().getValue(Tags.GAMES)

        assertTrue("the first source survives", games.containsMatchIn("a cricket match"))
        assertTrue("the second source is added", games.containsMatchIn("kabaddi league"))
    }

    @Test
    fun `a later description overrides an earlier one, because sentences do not union`() {
        val bundled = source("bundled", def(Tags.FINANCE, "bank", "money and markets"))
        val remote = source("remote", def(Tags.FINANCE, "bank", "a bespoke wording"))

        val descriptions = TagVocabulary(listOf(bundled, remote)).descriptions()

        assertEquals("a bespoke wording", descriptions[Tags.FINANCE])
    }

    @Test
    fun `a tag with no terms is described but absent from the lexicon`() {
        // `personal` is a sender fact, not a word - so it has no lexicon entry,
        // yet the embedding rung can still consider it.
        val vocabulary = TagVocabulary(listOf(source("s", def(Tags.PERSONAL, terms = "", description = "a person"))))

        assertFalse(Tags.PERSONAL in vocabulary.lexicon())
        assertEquals("a person", vocabulary.descriptions()[Tags.PERSONAL])
    }

    @Test
    fun `both shapes compile once and rebuild only on invalidate`() {
        var reads = 0
        val counting =
            object : TagVocabularySource {
                override val id = "counting"

                override fun definitions(): List<TagDefinition> {
                    reads++
                    return listOf(def(Tags.GAMES, "ipl", "sports"))
                }
            }

        val vocabulary = TagVocabulary(listOf(counting))
        vocabulary.lexicon()
        vocabulary.descriptions()
        vocabulary.lexicon()
        assertEquals("each shape reads the source once and caches", 2, reads)

        vocabulary.invalidate()
        vocabulary.lexicon()
        assertEquals("invalidate forces a rebuild", 3, reads)
    }

    @Test
    fun `underscores become spaces and matches are word-bounded`() {
        val vocabulary = TagVocabulary(listOf(source("m", def(Tags.GAMES, "world_cup|f1"))))
        val games = vocabulary.lexicon().getValue(Tags.GAMES)

        assertTrue(games.containsMatchIn("the world cup final"))
        assertFalse("unspaced", games.containsMatchIn("worldcup"))
        assertFalse("'f1' must not fire inside another token", games.containsMatchIn("nf1x"))
    }

    @Test
    fun `the bundled vocabulary defines every inferrable tag exactly once`() {
        val definitions = BundledVocabulary.definitions()
        val tags = definitions.map { it.tag }

        assertEquals("one definition per subject and nature", TagGroups.SUBJECTS + TagGroups.NATURES, tags.toSet())
        assertEquals("no tag is declared twice", tags.size, tags.toSet().size)
        assertTrue("no marker is described", tags.none { it in TagGroups.MARKERS })
        definitions.forEach { assertTrue("${it.tag} needs a description for the ML rung", it.description.isNotBlank()) }
    }
}
