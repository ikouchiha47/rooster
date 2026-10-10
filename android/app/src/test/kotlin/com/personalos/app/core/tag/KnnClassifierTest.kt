package com.personalos.app.core.tag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KnnClassifierTest {
    private val classifier = KnnClassifier(k = 3, minSimilarity = 0.30f)

    // A shared component plus one distinctive axis each — the anisotropy the
    // centering must cancel.
    private val finance = LabelledExample(floatArrayOf(3f, 1f, 1f), setOf("finance"))
    private val incident = LabelledExample(floatArrayOf(1f, 3f, 1f), setOf("incident"))
    private val games = LabelledExample(floatArrayOf(1f, 1f, 3f), setOf("games"))

    @Test
    fun `an example's own vector votes its own tags`() {
        assertEquals(setOf("finance"), classifier.classify(floatArrayOf(3f, 1f, 1f), listOf(finance, incident, games)))
    }

    @Test
    fun `a shared component alone is not a match`() {
        val result = classifier.classify(floatArrayOf(1f, 1f, 1f), listOf(finance, incident, games))
        assertTrue("nothing is close enough", result.isEmpty())
    }

    @Test
    fun `neighbours vote, and all their tags can surface`() {
        val result = classifier.classify(floatArrayOf(3f, 3f, 1f), listOf(finance, incident, games))
        assertTrue("both nearby tags", result.containsAll(setOf("finance", "incident")))
        assertTrue("the far one does not", "games" !in result)
    }

    @Test
    fun `no examples means no tags`() {
        assertTrue(classifier.classify(floatArrayOf(1f, 0f), emptyList()).isEmpty())
    }
}
