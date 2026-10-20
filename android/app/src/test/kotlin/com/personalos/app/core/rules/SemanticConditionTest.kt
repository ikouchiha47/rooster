package com.personalos.app.core.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticConditionTest {
    private fun item(vector: FloatArray?) =
        RuleItem(
            id = "item",
            sourceId = "sms",
            title = "x",
            content = "x",
            tags = emptySet(),
            mentions = emptyList(),
            embedding = vector,
        )

    @Test
    fun `a meaning round-trips through JSON`() {
        val vector = FloatArray(8) { it.toFloat() }
        val condition = Condition.Semantic("a strike by workers", vector, 0.6f)

        val parsed = ConditionJson.parse(ConditionStringify.stringify(condition))

        assertEquals("a strike by workers", (parsed as Condition.Semantic).text)
        assertEquals(0.6f, parsed.threshold, 1e-6f)
        assertTrue(vector.contentEquals(parsed.vector))
    }

    @Test
    fun `a stored vector at or above the threshold matches`() {
        val condition = Condition.Semantic("x", floatArrayOf(1f, 0f), 0.6f)
        assertTrue(RuleEvaluator.evaluate(item(floatArrayOf(1f, 0f)), condition))
    }

    @Test
    fun `a vector below the threshold does not match`() {
        val condition = Condition.Semantic("x", floatArrayOf(1f, 0f), 0.6f)
        assertFalse(RuleEvaluator.evaluate(item(floatArrayOf(0f, 1f)), condition))
    }

    @Test
    fun `no vector or a dimension mismatch is a non-match, not a crash`() {
        val condition = Condition.Semantic("x", floatArrayOf(1f, 0f), 0.6f)
        assertFalse(RuleEvaluator.evaluate(item(null), condition))
        assertFalse(RuleEvaluator.evaluate(item(floatArrayOf(1f, 0f, 0f)), condition))
    }
}
