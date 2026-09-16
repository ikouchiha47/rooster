package com.personalos.app.core.rules

import com.personalos.app.core.tag.Tags
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure item evaluator: each item predicate true and false, nested
 * composition, and the series-predicate boundary failing loudly.
 */
class RuleEvaluatorTest {
    private val item =
        RuleItem(
            id = "item-1",
            sourceId = "gnews:west-bengal",
            title = "Kolkata metro opens new stretch",
            content = "CRS inspection passed; a bandh was threatened.",
            tags = setOf(Tags.NEWS, Tags.TRAVEL, Tags.INCIDENT),
            mentions = listOf(RuleMention("place", "Kolkata"), RuleMention("party", "BJP")),
            fields =
                mapOf(
                    "amount" to FieldValue.Num(12000.0),
                    "sender" to FieldValue.Str("HDFC Bank"),
                    "priority" to FieldValue.Flag(true),
                ),
        )

    private fun holds(condition: Condition): Boolean = RuleEvaluator.evaluate(item, condition)

    // ------------------------------------------------------------ subject / nature

    @Test
    fun `subject holds only when the item carries that subject tag`() {
        assertTrue(holds(Condition.Subject(Tags.TRAVEL)))
        assertFalse(holds(Condition.Subject(Tags.GAMES)))
    }

    @Test
    fun `nature holds only when the item carries that nature tag`() {
        assertTrue(holds(Condition.Nature(Tags.INCIDENT)))
        assertFalse(holds(Condition.Nature(Tags.EXPENSE)))
    }

    // ------------------------------------------------------------------- marker

    @Test
    fun `the marker holds only when the item carries a marker tag`() {
        assertTrue(holds(Condition.Marker))
        assertFalse(RuleEvaluator.evaluate(item.copy(tags = setOf(Tags.TRAVEL)), Condition.Marker))
    }

    // ------------------------------------------------------------------ mention

    @Test
    fun `a mention holds only when kind and value both match`() {
        assertTrue(holds(Condition.Mention(kind = "place", value = "Kolkata")))
        assertTrue(holds(Condition.Mention(kind = "party", value = "BJP")))
        assertFalse(holds(Condition.Mention(kind = "place", value = "Delhi")))
        assertFalse(holds(Condition.Mention(kind = "party", value = "Kolkata")))
    }

    // ------------------------------------------------------------------- source

    @Test
    fun `source holds only for the stored source identity`() {
        assertTrue(holds(Condition.Source("gnews:west-bengal")))
        assertFalse(holds(Condition.Source("the-hindu")))
    }

    // -------------------------------------------------------------------- field

    @Test
    fun `field comparisons read the typed value`() {
        assertTrue(holds(field("amount", FieldOp.GT, FieldValue.Num(10000.0))))
        assertFalse(holds(field("amount", FieldOp.GT, FieldValue.Num(20000.0))))
        assertTrue(holds(field("amount", FieldOp.GTE, FieldValue.Num(12000.0))))
        assertTrue(holds(field("amount", FieldOp.LT, FieldValue.Num(20000.0))))
        assertTrue(holds(field("amount", FieldOp.LTE, FieldValue.Num(12000.0))))
        assertTrue(holds(field("amount", FieldOp.EQ, FieldValue.Num(12000.0))))
        assertTrue(holds(field("amount", FieldOp.NE, FieldValue.Num(1.0))))
        assertTrue(holds(field("sender", FieldOp.CONTAINS, FieldValue.Str("HDFC"))))
        assertFalse(holds(field("sender", FieldOp.CONTAINS, FieldValue.Str("ICICI"))))
        assertTrue(holds(field("priority", FieldOp.EQ, FieldValue.Flag(true))))
    }

    @Test
    fun `an absent field matches nothing, including ne`() {
        assertFalse(holds(field("missing", FieldOp.EQ, FieldValue.Num(1.0))))
        assertFalse(holds(field("missing", FieldOp.NE, FieldValue.Num(1.0))))
        assertFalse(holds(field("missing", FieldOp.GT, FieldValue.Num(1.0))))
        assertFalse(holds(field("missing", FieldOp.CONTAINS, FieldValue.Str("x"))))
    }

    @Test
    fun `a numeric operator over a text field matches nothing`() {
        assertFalse(holds(field("sender", FieldOp.GT, FieldValue.Num(1.0))))
    }

    // --------------------------------------------------------------------- text

    @Test
    fun `text targets title, content or either`() {
        assertTrue(holds(Condition.Text(TextMatcher("metro", TextTarget.TITLE))))
        assertFalse(holds(Condition.Text(TextMatcher("metro", TextTarget.CONTENT))))
        assertTrue(holds(Condition.Text(TextMatcher("metro", TextTarget.ANY))))

        assertTrue(holds(Condition.Text(TextMatcher("bandh", TextTarget.CONTENT))))
        assertFalse(holds(Condition.Text(TextMatcher("bandh", TextTarget.TITLE))))
        assertTrue(holds(Condition.Text(TextMatcher("bandh", TextTarget.ANY))))

        assertFalse(holds(Condition.Text(TextMatcher("strike", TextTarget.ANY))))
    }

    // -------------------------------------------------------------- composition

    @Test
    fun `all holds only when every child holds`() {
        assertTrue(
            holds(
                Condition.All(
                    listOf(
                        Condition.Subject(Tags.TRAVEL),
                        Condition.Mention(kind = "place", value = "Kolkata"),
                    ),
                ),
            ),
        )
        assertFalse(
            holds(
                Condition.All(
                    listOf(
                        Condition.Subject(Tags.TRAVEL),
                        Condition.Subject(Tags.GAMES),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `any holds when at least one child holds`() {
        assertTrue(
            holds(
                Condition.Any(
                    listOf(
                        Condition.Subject(Tags.GAMES),
                        Condition.Subject(Tags.TRAVEL),
                    ),
                ),
            ),
        )
        assertFalse(
            holds(
                Condition.Any(
                    listOf(
                        Condition.Subject(Tags.GAMES),
                        Condition.Nature(Tags.EXPENSE),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `all and any nest`() {
        val nested =
            Condition.All(
                listOf(
                    Condition.Subject(Tags.TRAVEL),
                    Condition.Any(
                        listOf(
                            Condition.Nature(Tags.EXPENSE),
                            Condition.All(
                                listOf(
                                    Condition.Source("gnews:west-bengal"),
                                    Condition.Text(TextMatcher("bandh", TextTarget.ANY)),
                                ),
                            ),
                        ),
                    ),
                ),
            )
        assertTrue(holds(nested))
    }

    // ------------------------------------------------- series predicates fail loudly

    @Test
    fun `each series predicate throws rather than answering false`() {
        val series =
            listOf(
                Condition.Crossing("price", CrossingDirection.BELOW, 30000.0, Window(5)),
                Condition.Delta("price", -5000.0, Window(7)),
                Condition.Min("price", FieldOp.LT, 30000.0, Window(7)),
                Condition.Max("score", FieldOp.GTE, 90.0, Window(2)),
            )
        for (condition in series) {
            val error =
                assertThrows(SeriesPredicateUnsupportedException::class.java) {
                    RuleEvaluator.evaluate(item, condition)
                }
            assertTrue(
                "the message names the window, not just a crash: ${error.message}",
                error.message!!.contains("window"),
            )
        }
    }

    @Test
    fun `a series child cannot hide behind a false sibling in all`() {
        // `subject games` is false for this item. A short-circuiting `all` would
        // return false without ever reaching the series child; the evaluator
        // must refuse the whole condition instead.
        val condition =
            Condition.All(
                listOf(
                    Condition.Subject(Tags.GAMES),
                    Condition.Crossing("price", CrossingDirection.BELOW, 30000.0, Window(5)),
                ),
            )
        assertThrows(SeriesPredicateUnsupportedException::class.java) {
            RuleEvaluator.evaluate(item, condition)
        }
    }

    private fun field(
        name: String,
        op: FieldOp,
        value: FieldValue,
    ) = Condition.Field(name = name, op = op, value = value)
}
