package com.personalos.app.core.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchedClausesTest {
    private val item =
        RuleItem(
            id = "obs-1",
            sourceId = "weather:bengaluru",
            title = "Bengaluru",
            content = "40.2C",
            tags = setOf("weather", "news"),
            mentions = emptyList(),
            fields =
                mapOf(
                    "temp_c" to FieldValue.Num(40.2),
                    "humidity_pct" to FieldValue.Num(90.0),
                    "amount" to FieldValue.Num(12345.0),
                ),
        )

    @Test
    fun `a fired field clause prints the stored value against the threshold`() {
        val clauses = Condition.Field("temp_c", FieldOp.GT, FieldValue.Num(40.0)).matchedClauses(item)
        assertEquals(listOf("temp_c 40.2 > 40"), clauses.map { it.summary })
    }

    @Test
    fun `whole numbers lose the trailing zero`() {
        val clauses = Condition.Field("amount", FieldOp.GT, FieldValue.Num(10_000.0)).matchedClauses(item)
        assertEquals(listOf("amount 12345 > 10000"), clauses.map { it.summary })
    }

    @Test
    fun `all reports every clause that held`() {
        val condition =
            Condition.All(
                listOf(
                    Condition.Field("temp_c", FieldOp.GT, FieldValue.Num(40.0)),
                    Condition.Source("weather:bengaluru"),
                ),
            )
        assertEquals(
            listOf("temp_c 40.2 > 40", "source weather:bengaluru"),
            condition.matchedClauses(item).map { it.summary },
        )
    }

    @Test
    fun `any reports only the branch that held`() {
        val condition =
            Condition.Any(
                listOf(
                    Condition.Field("temp_c", FieldOp.LT, FieldValue.Num(0.0)),
                    Condition.Field("humidity_pct", FieldOp.GT, FieldValue.Num(80.0)),
                ),
            )
        assertEquals(listOf("humidity_pct 90 > 80"), condition.matchedClauses(item).map { it.summary })
    }

    @Test
    fun `a clause that did not hold is never reported`() {
        assertTrue(Condition.Field("temp_c", FieldOp.LT, FieldValue.Num(0.0)).matchedClauses(item).isEmpty())
        assertTrue(Condition.Subject("travel").matchedClauses(item).isEmpty())
    }

    @Test
    fun `text and tag clauses describe themselves`() {
        assertEquals(
            listOf("text ~ \"bengaluru\""),
            Condition.Text(TextMatcher("bengaluru", TextTarget.ANY)).matchedClauses(item).map { it.summary },
        )
        assertEquals(
            listOf("subject weather"),
            Condition.Subject("weather").matchedClauses(item).map { it.summary },
        )
    }

    @Test
    fun `a series clause contributes nothing rather than throwing`() {
        val condition =
            Condition.All(
                listOf(
                    Condition.Source("weather:bengaluru"),
                    Condition.Crossing("temp_c", CrossingDirection.ABOVE, 40.0, Window(3)),
                ),
            )
        assertEquals(listOf("source weather:bengaluru"), condition.matchedClauses(item).map { it.summary })
    }
}
