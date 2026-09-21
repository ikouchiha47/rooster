package com.personalos.app.core.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTest {
    private fun facet(
        id: String,
        valueType: ValueType = ValueType.STR,
        measure: MeasureKind = MeasureKind.COUNTER,
        ops: Set<String> = setOf("eq"),
    ) = Facet(id = id, valueType = valueType, measure = measure, ops = ops)

    private fun kind(
        id: String,
        topics: Set<String> = setOf("news"),
        facetIds: Set<String> = emptySet(),
    ) = Kind(id = id, grain = IngestGrain.ITEM, topics = topics, facetIds = facetIds, enrichable = true)

    @Test
    fun `facet by id exposes type measure and ops`() {
        val catalog =
            InMemoryCatalog(
                kinds = listOf(kind("rss", facetIds = setOf("subject"))),
                facets = listOf(facet("subject", ValueType.ENUM, MeasureKind.COUNTER, setOf("eq", "in"))),
            )
        val found = catalog.facet("subject")!!
        assertEquals(ValueType.ENUM, found.valueType)
        assertEquals(MeasureKind.COUNTER, found.measure)
        assertTrue(found.ops.isNotEmpty())
    }

    @Test
    fun `weather topic unions rss subject and gauge temp`() {
        val catalog =
            InMemoryCatalog(
                kinds =
                    listOf(
                        kind("rss", topics = setOf("weather"), facetIds = setOf("subject")),
                        kind("weather", topics = setOf("weather"), facetIds = setOf("temp_c")),
                    ),
                facets =
                    listOf(
                        facet("subject", ValueType.ENUM, MeasureKind.COUNTER, setOf("eq", "in")),
                        facet("temp_c", ValueType.NUM, MeasureKind.GAUGE, setOf("eq", "gt", "crossing")),
                    ),
            )
        val ids = catalog.facetsForTopics(setOf("weather")).map { it.id }.toSet()
        assertTrue("temp_c" in ids && "subject" in ids)
    }

    @Test
    fun `kind with no facets contributes none without dropping others`() {
        val catalog =
            InMemoryCatalog(
                kinds =
                    listOf(
                        kind("rss", topics = setOf("news"), facetIds = setOf("subject")),
                        kind("empty", topics = setOf("news"), facetIds = emptySet()),
                    ),
                facets = listOf(facet("subject")),
            )
        val ids = catalog.facetsForTopics(setOf("news")).map { it.id }
        assertEquals(listOf("subject"), ids)
    }

    @Test
    fun `unknown op in seed fails closed`() {
        assertThrows(IllegalArgumentException::class.java) {
            InMemoryCatalog(
                kinds = listOf(kind("rss", facetIds = setOf("subject"))),
                facets = listOf(facet("subject", ops = setOf("bogus"))),
            )
        }
    }

    @Test
    fun `conflicting facet rows fail closed`() {
        assertThrows(IllegalArgumentException::class.java) {
            InMemoryCatalog(
                kinds = listOf(kind("rss", facetIds = setOf("subject"))),
                facets =
                    listOf(
                        facet("subject", ValueType.ENUM, MeasureKind.COUNTER),
                        facet("subject", ValueType.NUM, MeasureKind.COUNTER),
                    ),
            )
        }
    }
}
