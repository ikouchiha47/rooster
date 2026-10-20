package com.personalos.app.core.tag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadClassifierTest {
    private fun head(
        tags: List<String>,
        weights: FloatArray,
        bias: Float = 0f,
        threshold: Float = 0.5f,
    ) = Head(
        label = tags.firstOrNull() ?: "none",
        weights = weights,
        bias = bias,
        threshold = threshold,
        tags = tags.toSet(),
    )

    @Test
    fun `a confident head emits its mapped tag`() {
        val classifier = HeadClassifier(listOf(head(listOf("finance"), floatArrayOf(1f, 0f))))
        assertEquals(mapOf("finance" to 1f / (1f + kotlin.math.exp(-1f))), classifier.classify(floatArrayOf(1f, 0f)))
    }

    @Test
    fun `a threshold above the probability emits nothing`() {
        val classifier = HeadClassifier(listOf(head(listOf("finance"), floatArrayOf(1f, 0f), threshold = 0.9f)))
        assertTrue(classifier.classify(floatArrayOf(1f, 0f)).isEmpty())
    }

    @Test
    fun `two heads may map to one tag and the best probability wins`() {
        val classifier =
            HeadClassifier(
                listOf(
                    head(listOf("incident"), floatArrayOf(1f, 0f)),
                    head(listOf("incident"), floatArrayOf(0f, 1f)),
                ),
            )
        val scores = classifier.classify(floatArrayOf(0.2f, 1f))
        assertEquals(setOf("incident"), scores.keys)
        assertEquals(1f / (1f + kotlin.math.exp(-1f)), scores.getValue("incident"), 1e-6f)
    }

    @Test
    fun `a head with no mapped tags is trained but never inferred`() {
        val classifier = HeadClassifier(listOf(head(emptyList(), floatArrayOf(1f, 0f))))
        assertTrue(classifier.classify(floatArrayOf(1f, 0f)).isEmpty())
    }

    @Test
    fun `a dimension mismatch is ignored rather than crashing`() {
        val classifier = HeadClassifier(listOf(head(listOf("finance"), floatArrayOf(1f, 0f))))
        assertTrue(classifier.classify(floatArrayOf(1f, 0f, 0f)).isEmpty())
    }
}
