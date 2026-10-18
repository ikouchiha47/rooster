package com.personalos.app.core.tag

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyTaggerTest {
    private fun fake(
        tags: Set<String>,
        confidence: Float = 0.9f,
    ) = object : Tagger {
        override val id = "fake"
        override val kind = TaggerKind.HEURISTIC
        override val version = 1

        override suspend fun tag(input: TagInput) = TagResult(tags = tags, confidence = confidence, taggerId = id)
    }

    private fun input(declared: Set<String> = emptySet()) = TagInput(text = "x", source = Transport.SMS, declaredTags = declared)

    @Test
    fun `the lexicon may contribute a contextual tag, and the embedder adds its own`() {
        // The lexicon is rung 0 and covers every inferable leaf, so its hit stands
        // alongside the embedder's reading rather than being dropped.
        val tagger = PolicyTagger(fake(setOf(Tags.TRAVEL)), fake(setOf(Tags.FINANCE, Tags.INCIDENT)))

        val tags = runBlocking { tagger.tag(input()) }.tags

        assertEquals(setOf(Tags.TRAVEL, Tags.FINANCE, Tags.INCIDENT), tags)
    }

    @Test
    fun `the lexicon keeps a curated tag the embedder missed`() {
        val tagger = PolicyTagger(fake(setOf(Tags.FESTIVAL)), fake(emptySet()))

        assertTrue(Tags.FESTIVAL in runBlocking { tagger.tag(input()) }.tags)
    }

    @Test
    fun `neither rung can infer a marker, but a declared one passes`() {
        val tagger = PolicyTagger(fake(emptySet()), fake(setOf(Tags.NEWS, Tags.WEATHER)))

        val tags = runBlocking { tagger.tag(input(declared = setOf(Tags.NEWS))) }.tags

        assertTrue(Tags.NEWS in tags)
        assertTrue(Tags.WEATHER in tags)

        // And a marker *only* inferred by the embedder is refused.
        val inferred = runBlocking { PolicyTagger(fake(emptySet()), fake(setOf(Tags.NEWS))).tag(input()) }
        assertFalse(Tags.NEWS in inferred.tags)
    }

    @Test
    fun `confidence is the higher of the two`() {
        val tagger = PolicyTagger(fake(setOf(Tags.FESTIVAL), confidence = 0.3f), fake(setOf(), confidence = 0.8f))
        assertEquals(0.8f, runBlocking { tagger.tag(input()) }.confidence, 0.0001f)
    }
}
