package com.personalos.app.core.tag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TagSelectionTest {
    @Test
    fun `only tags above the threshold survive`() {
        val selection =
            selectTags(
                scores = mapOf(Tags.FINANCE to 0.40f, Tags.TECH to 0.10f),
                declaredTags = emptySet(),
                fromAdvert = false,
            )
        assertEquals(setOf(Tags.FINANCE), selection.tags)
    }

    @Test
    fun `a marker score is never inferred`() {
        // `news` is a marker, not a subject or nature: the model must not emit it.
        val selection =
            selectTags(
                scores = mapOf(Tags.NEWS to 0.99f, Tags.WEATHER to 0.70f),
                declaredTags = emptySet(),
                fromAdvert = false,
            )
        assertFalse(Tags.NEWS in selection.tags)
        assertTrue(Tags.WEATHER in selection.tags)
    }

    @Test
    fun `an advert yields no subject, but its nature still fires`() {
        val selection =
            selectTags(
                scores = mapOf(Tags.WEATHER to 0.90f, Tags.PROMO to 0.80f),
                declaredTags = emptySet(),
                fromAdvert = true,
            )
        assertFalse("marketing copy is not evidence of weather", Tags.WEATHER in selection.tags)
        assertTrue("the promo nature still applies", Tags.PROMO in selection.tags)
    }

    @Test
    fun `subjects and natures are capped, best first`() {
        val selection =
            selectTags(
                scores =
                    mapOf(
                        Tags.FINANCE to 0.80f,
                        Tags.TECH to 0.75f,
                        Tags.TRAVEL to 0.70f,
                        Tags.INCIDENT to 0.60f,
                        Tags.OFFICIAL to 0.55f,
                        Tags.EXPENSE to 0.50f,
                        Tags.PROMO to 0.48f,
                    ),
                declaredTags = emptySet(),
                fromAdvert = false,
            )
        // 2 subjects, 3 natures at most.
        assertEquals(2, selection.tags.count { it in TagGroups.SUBJECTS })
        assertEquals(3, selection.tags.count { it in TagGroups.NATURES })
        assertTrue(Tags.FINANCE in selection.tags)
        assertFalse("third-best subject is dropped", Tags.TRAVEL in selection.tags)
    }

    @Test
    fun `a declared tag is kept even when the model scores nothing`() {
        val selection =
            selectTags(
                scores = emptyMap(),
                declaredTags = setOf(Tags.FINANCE, Tags.NEWS),
                fromAdvert = false,
            )
        assertEquals(setOf(Tags.FINANCE, Tags.NEWS), selection.tags)
        assertEquals(1f, selection.confidence, 0.0001f)
    }

    @Test
    fun `nothing scoring is nothing tagged`() {
        val selection = selectTags(emptyMap(), emptySet(), fromAdvert = false)
        assertTrue(selection.tags.isEmpty())
        assertEquals(0f, selection.confidence, 0.0001f)
    }

    // ------------------------------------------------------------- margin + unsure

    @Test
    fun `a tag far below the best is dropped by the margin`() {
        // Both clear the floor, but only the leader is close to the leader: a distant
        // runner-up (a whole different subject) must not ride along on a top score.
        val selection =
            selectTags(
                scores = mapOf(Tags.FINANCE to 0.40f, Tags.TECH to 0.28f),
                declaredTags = emptySet(),
                fromAdvert = false,
            )
        assertTrue(Tags.FINANCE in selection.tags)
        assertFalse("a distant runner-up does not ride along", Tags.TECH in selection.tags)
    }

    @Test
    fun `a flat field yields nothing and is unsure`() {
        // Every score sits just under the floor and within the margin of it — the
        // classic anisotropy case. The honest answer is "no tag", flagged unsure
        // (so a later LLM rung can be asked), not a coin-flip tag.
        val selection =
            selectTags(
                scores = mapOf(Tags.FINANCE to 0.15f, Tags.TECH to 0.14f, Tags.TRAVEL to 0.13f),
                declaredTags = emptySet(),
                fromAdvert = false,
            )
        assertTrue(selection.tags.isEmpty())
        assertTrue("borderline, not absent", selection.unsure)
    }

    @Test
    fun `a far-below field is neither tagged nor unsure`() {
        val selection =
            selectTags(
                scores = mapOf(Tags.FINANCE to 0.05f, Tags.TECH to 0.04f),
                declaredTags = emptySet(),
                fromAdvert = false,
            )
        assertTrue(selection.tags.isEmpty())
        assertFalse("nowhere near the bar is not uncertainty", selection.unsure)
    }

    @Test
    fun `a confident choice is not unsure`() {
        val selection =
            selectTags(
                scores = mapOf(Tags.FINANCE to 0.80f),
                declaredTags = emptySet(),
                fromAdvert = false,
            )
        assertFalse(selection.unsure)
    }
}
