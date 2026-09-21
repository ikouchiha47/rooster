package com.personalos.app.core.catalog

import com.personalos.app.core.rules.ConditionJson
import com.personalos.app.core.rules.FieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftCompilerTest {
    private val catalog = CatalogSeeds.catalog()

    @Test
    fun `field options equal catalog facet ids for the topic`() {
        val options = DraftCompiler.fieldOptions(catalog, "weather")
        assertEquals(catalog.facetsForTopics(setOf("weather")).map { it.id }, options)
        assertTrue("temp_c" in options)
    }

    @Test
    fun `op options equal the facet ops`() {
        assertEquals(catalog.facet("temp_c")!!.ops, DraftCompiler.opOptions(catalog, "temp_c"))
    }

    @Test
    fun `a valid draft saves JSON that parses`() {
        val json =
            DraftCompiler.compileAll(
                listOf(
                    FacetClause.Single("subject", "eq", FieldValue.Str("weather")),
                    FacetClause.Single("amount", "gt", FieldValue.Num(10000.0)),
                ),
                catalog,
            )
        val parsed = ConditionJson.parse(json)
        assertTrue(parsed is com.personalos.app.core.rules.Condition.All)
    }
}
