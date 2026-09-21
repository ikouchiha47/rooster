package com.personalos.app.core.catalog

import com.personalos.app.core.rules.Condition
import com.personalos.app.core.rules.ConditionJson
import com.personalos.app.core.rules.CrossingDirection
import com.personalos.app.core.rules.FieldOp
import com.personalos.app.core.rules.FieldValue
import com.personalos.app.core.rules.TextMatcher
import com.personalos.app.core.rules.TextTarget
import com.personalos.app.core.rules.Window
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FacetCompilerTest {
    private val catalog: Catalog = CatalogSeeds.catalog()

    private fun single(
        facetId: String,
        op: String,
        value: FieldValue,
        window: Int? = null,
        direction: String? = null,
    ) = FacetClause.Single(facetId = facetId, op = op, value = value, window = window, direction = direction)

    @Test
    fun `subject nature marker source compile to tag leaves`() {
        assertEquals(
            Condition.Subject("weather"),
            FacetCompiler.toCondition(single("subject", "eq", FieldValue.Str("weather")), catalog),
        )
        assertEquals(
            Condition.Nature("incident"),
            FacetCompiler.toCondition(single("nature", "eq", FieldValue.Str("incident")), catalog),
        )
        assertEquals(
            Condition.Marker,
            FacetCompiler.toCondition(single("marker", "eq", FieldValue.Flag(true)), catalog),
        )
        assertEquals(
            Condition.Source("sms"),
            FacetCompiler.toCondition(single("source", "eq", FieldValue.Str("sms")), catalog),
        )
    }

    @Test
    fun `amount gt compiles to field`() {
        assertEquals(
            Condition.Field(name = "amount", op = FieldOp.GT, value = FieldValue.Num(10000.0)),
            FacetCompiler.toCondition(single("amount", "gt", FieldValue.Num(10000.0)), catalog),
        )
    }

    @Test
    fun `temp_c crossing compiles to series leaf`() {
        assertEquals(
            Condition.Crossing(field = "temp_c", direction = CrossingDirection.ABOVE, threshold = 40.0, window = Window(6)),
            FacetCompiler.toCondition(
                single("temp_c", "crossing", FieldValue.Num(40.0), window = 6, direction = "above"),
                catalog,
            ),
        )
    }

    @Test
    fun `unknown facet throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            FacetCompiler.toCondition(single("nope", "eq", FieldValue.Str("x")), catalog)
        }
    }

    @Test
    fun `compiled clauses round-trip through ConditionJson`() {
        val compiled =
            FacetCompiler.toCondition(single("amount", "gt", FieldValue.Num(10000.0)), catalog)
        val parsed = ConditionJson.parse("""{"field": {"name": "amount", "op": "gt", "value": 10000}}""")
        assertEquals(parsed, compiled)
    }

    @Test
    fun `title and content compile to text`() {
        assertEquals(
            Condition.Text(TextMatcher("bandh", TextTarget.TITLE)),
            FacetCompiler.toCondition(single("title", "contains", FieldValue.Str("bandh")), catalog),
        )
        assertEquals(
            Condition.Text(TextMatcher("flood", TextTarget.CONTENT)),
            FacetCompiler.toCondition(single("content", "contains", FieldValue.Str("flood")), catalog),
        )
    }

    @Test
    fun `place and party compile to mention`() {
        assertEquals(
            Condition.Mention(kind = "place", value = "Kolkata"),
            FacetCompiler.toCondition(single("place", "eq", FieldValue.Str("Kolkata")), catalog),
        )
        assertEquals(
            Condition.Mention(kind = "party", value = "BJP"),
            FacetCompiler.toCondition(single("party", "eq", FieldValue.Str("BJP")), catalog),
        )
    }
}
