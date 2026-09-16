package com.personalos.app.core.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConditionJsonTest {
    // ----------------------------------------------------- parse success per kind

    @Test
    fun `every item and series predicate parses to its model`() {
        val cases =
            listOf(
                """{"subject": "games"}""" to Condition.Subject("games"),
                """{"nature": "incident"}""" to Condition.Nature("incident"),
                """{"marker": true}""" to Condition.Marker,
                """{"mention": {"kind": "place", "value": "Kolkata"}}""" to
                    Condition.Mention(kind = "place", value = "Kolkata"),
                """{"source": "the-hindu"}""" to Condition.Source("the-hindu"),
                """{"field": {"name": "amount", "op": "gt", "value": 10000}}""" to
                    Condition.Field(name = "amount", op = FieldOp.GT, value = FieldValue.Num(10000.0)),
                """{"field": {"name": "sender", "op": "contains", "value": "HDFC"}}""" to
                    Condition.Field(name = "sender", op = FieldOp.CONTAINS, value = FieldValue.Str("HDFC")),
                """{"field": {"name": "priority", "op": "eq", "value": true}}""" to
                    Condition.Field(name = "priority", op = FieldOp.EQ, value = FieldValue.Flag(true)),
                """{"text": {"pattern": "bandh", "target": "any"}}""" to
                    Condition.Text(TextMatcher("bandh", TextTarget.ANY)),
                """{"text": {"pattern": "flood", "target": "title"}}""" to
                    Condition.Text(TextMatcher("flood", TextTarget.TITLE)),
                """{"text": {"pattern": "flood", "target": "content"}}""" to
                    Condition.Text(TextMatcher("flood", TextTarget.CONTENT)),
                """{"crossing": {"field": "price", "direction": "below", "value": 30000, "window": 5}}""" to
                    Condition.Crossing("price", CrossingDirection.BELOW, 30000.0, Window(5)),
                """{"crossing": {"field": "price", "direction": "above", "value": 50000, "window": 3}}""" to
                    Condition.Crossing("price", CrossingDirection.ABOVE, 50000.0, Window(3)),
                """{"delta": {"field": "price", "by": -5000, "window": 7}}""" to
                    Condition.Delta("price", -5000.0, Window(7)),
                """{"min": {"field": "price", "op": "lt", "value": 30000, "window": 7}}""" to
                    Condition.Min("price", FieldOp.LT, 30000.0, Window(7)),
                """{"max": {"field": "score", "op": "gte", "value": 90, "window": 2}}""" to
                    Condition.Max("score", FieldOp.GTE, 90.0, Window(2)),
            )

        for ((json, expected) in cases) {
            assertEquals("parsing $json", expected, ConditionJson.parse(json))
        }
    }

    @Test
    fun `all and any nest arbitrarily`() {
        val json =
            """
            {"all": [
              {"subject": "games"},
              {"any": [
                {"nature": "incident"},
                {"text": {"pattern": "bandh", "target": "any"}}
              ]}
            ]}
            """.trimIndent()

        val expected =
            Condition.All(
                listOf(
                    Condition.Subject("games"),
                    Condition.Any(
                        listOf(
                            Condition.Nature("incident"),
                            Condition.Text(TextMatcher("bandh", TextTarget.ANY)),
                        ),
                    ),
                ),
            )

        assertEquals(expected, ConditionJson.parse(json))
    }

    // ---------------------------------------------------------- unknown keys (P7)

    @Test
    fun `a misspelled predicate key is rejected`() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                ConditionJson.parse("""{"subjekt": "games"}""")
            }
        assertTrue(error.message!!.contains("subjekt"))
    }

    @Test
    fun `a second key on a condition node is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConditionJson.parse("""{"subject": "games", "nature": "incident"}""")
        }
    }

    @Test
    fun `a misspelled key inside a leaf is rejected`() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                ConditionJson.parse("""{"text": {"pattern": "bandh", "targte": "any"}}""")
            }
        assertTrue(error.message!!.contains("targte"))
    }

    @Test
    fun `a misspelled key in a nested child is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConditionJson.parse("""{"all": [{"source": "x", "bogus": 1}]}""")
        }
    }

    @Test
    fun `an unknown field operator is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConditionJson.parse("""{"field": {"name": "amount", "op": "matches", "value": 1}}""")
        }
    }

    @Test
    fun `an unknown text target is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConditionJson.parse("""{"text": {"pattern": "x", "target": "body"}}""")
        }
    }

    // ------------------------------------------------------------- value validity

    @Test
    fun `a tag is checked against its group`() {
        // `incident` is a nature, not a subject; `games` is a subject, not a nature.
        assertThrows(IllegalArgumentException::class.java) {
            ConditionJson.parse("""{"subject": "incident"}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConditionJson.parse("""{"nature": "games"}""")
        }
    }

    @Test
    fun `the marker cannot be negated`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConditionJson.parse("""{"marker": false}""")
        }
    }

    @Test
    fun `a numeric operator needs a number`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConditionJson.parse("""{"field": {"name": "amount", "op": "gt", "value": "lots"}}""")
        }
    }

    @Test
    fun `contains needs a string`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConditionJson.parse("""{"field": {"name": "amount", "op": "contains", "value": 1}}""")
        }
    }

    @Test
    fun `an empty composition is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConditionJson.parse("""{"all": []}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConditionJson.parse("""{"any": []}""")
        }
    }

    @Test
    fun `a series window must be positive`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConditionJson.parse("""{"crossing": {"field": "price", "direction": "below", "value": 1, "window": 0}}""")
        }
    }

    @Test
    fun `an aggregate cannot use a non numeric operator`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConditionJson.parse("""{"min": {"field": "price", "op": "eq", "value": 1, "window": 2}}""")
        }
    }

    @Test
    fun `an invalid regex is rejected at the write gate`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConditionJson.parse("""{"text": {"pattern": "[unclosed", "target": "any"}}""")
        }
    }

    @Test
    fun `an overlong pattern is rejected`() {
        val long = "a".repeat(TextMatcher.MAX_PATTERN_LENGTH + 1)
        assertThrows(IllegalArgumentException::class.java) {
            ConditionJson.parse("""{"text": {"pattern": "$long", "target": "any"}}""")
        }
    }

    @Test
    fun `malformed json and non objects are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { ConditionJson.parse("{\"subject\": \"games") }
        assertThrows(IllegalArgumentException::class.java) { ConditionJson.parse("""["subject"]""") }
    }

    // ------------------------------------------------- enrichment sensitivity (P4)

    @Test
    fun `a text predicate anywhere marks the condition enrichment sensitive`() {
        assertTrue(Condition.Text(TextMatcher("bandh", TextTarget.ANY)).isEnrichmentSensitive())
        assertFalse(Condition.Subject("games").isEnrichmentSensitive())
        assertFalse(
            Condition
                .All(
                    listOf(Condition.Subject("games"), Condition.Nature("incident")),
                ).isEnrichmentSensitive(),
        )
    }

    @Test
    fun `enrichment sensitivity recurses through nested composition`() {
        val nested =
            Condition.All(
                listOf(
                    Condition.Subject("games"),
                    Condition.Any(
                        listOf(
                            Condition.Nature("incident"),
                            Condition.All(
                                listOf(Condition.Source("the-hindu")),
                            ),
                        ),
                    ),
                ),
            )
        assertFalse(nested.isEnrichmentSensitive())

        val withText =
            Condition.All(
                listOf(
                    Condition.Subject("games"),
                    Condition.Any(
                        listOf(
                            Condition.Nature("incident"),
                            Condition.All(
                                listOf(Condition.Text(TextMatcher("bandh", TextTarget.ANY))),
                            ),
                        ),
                    ),
                ),
            )
        assertTrue(withText.isEnrichmentSensitive())
    }
}
