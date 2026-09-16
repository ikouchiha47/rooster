package com.personalos.app.core.tag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TagGroupsTest {
    private val groups = listOf(TagGroups.SUBJECTS, TagGroups.NATURES, TagGroups.MARKERS)

    @Test
    fun `the three groups partition every tag exactly once`() {
        // Every group member is a real tag, no tag is missing, and none is in two
        // groups. An ungrouped tag fails here rather than silently becoming a nature.
        assertTrue("group members must be known tags", Tags.ALL.containsAll(groups.flatten()))
        assertEquals("no tag may be in two groups", Tags.ALL.size, groups.sumOf { it.size })
        assertEquals("the groups must cover every tag", Tags.ALL, groups.flatten().toSet())
    }

    @Test
    fun `news is the marker and nothing else`() {
        assertEquals(setOf(Tags.NEWS), TagGroups.MARKERS)
        assertFalse(Tags.NEWS in TagGroups.SUBJECTS)
        assertFalse(Tags.NEWS in TagGroups.NATURES)
    }

    @Test
    fun `the groups are pairwise disjoint`() {
        assertTrue((TagGroups.SUBJECTS intersect TagGroups.NATURES).isEmpty())
        assertTrue((TagGroups.SUBJECTS intersect TagGroups.MARKERS).isEmpty())
        assertTrue((TagGroups.NATURES intersect TagGroups.MARKERS).isEmpty())
    }

    @Test
    fun `subjects and natures are the tags the tagger already uses`() {
        // The 7 subjects were the tagger's previous private SUBJECTS copy; the 5
        // natures are what remained once the marker was removed. No tag invented.
        assertEquals(
            setOf(Tags.FINANCE, Tags.TECH, Tags.TRAVEL, Tags.FESTIVAL, Tags.GAMES, Tags.WEATHER, Tags.PAPER),
            TagGroups.SUBJECTS,
        )
        assertEquals(
            setOf(Tags.EXPENSE, Tags.PROMO, Tags.INCIDENT, Tags.PERSONAL, Tags.OFFICIAL),
            TagGroups.NATURES,
        )
    }
}
