package com.personalos.app.data

import com.personalos.app.core.catalog.CatalogSeeds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogSeederTest {
    private class FakeKindDao(
        val rows: MutableList<KindEntity> = mutableListOf(),
    ) : KindDao {
        override fun observeAll(): Flow<List<KindEntity>> = flowOf(rows.toList())

        override suspend fun all(): List<KindEntity> = rows.toList()

        override suspend fun insertAll(kinds: List<KindEntity>): List<Long> =
            kinds.map { row ->
                if (rows.none { it.id == row.id }) {
                    rows += row
                    1L
                } else {
                    -1L
                }
            }
    }

    private class FakeFacetDao(
        val rows: MutableList<FacetEntity> = mutableListOf(),
    ) : FacetDao {
        override fun observeAll(): Flow<List<FacetEntity>> = flowOf(rows.toList())

        override suspend fun all(): List<FacetEntity> = rows.toList()

        override suspend fun insertAll(facets: List<FacetEntity>): List<Long> =
            facets.map { row ->
                if (rows.none { it.id == row.id }) {
                    rows += row
                    1L
                } else {
                    -1L
                }
            }
    }

    private class FakeJoinDao(
        val rows: MutableList<KindFacetEntity> = mutableListOf(),
    ) : KindFacetDao {
        override suspend fun all(): List<KindFacetEntity> = rows.toList()

        override suspend fun insertAll(rows: List<KindFacetEntity>): List<Long> =
            rows.map { row ->
                if (this.rows.none { it.kindId == row.kindId && it.facetId == row.facetId }) {
                    this.rows += row
                    1L
                } else {
                    -1L
                }
            }
    }

    @Test
    fun `seed writes the bundled kinds and facets once`() =
        runBlocking {
            val kinds = FakeKindDao()
            val facets = FakeFacetDao()
            val joins = FakeJoinDao()
            val seeder = CatalogSeeder(kinds, facets, joins)
            val first = seeder.seed(0L)
            assertTrue(first > 0)
            val second = seeder.seed(0L)
            assertEquals(0, second)
            assertEquals(CatalogSeeds.kinds().size, kinds.rows.size)
            assertEquals(CatalogSeeds.facets().size, facets.rows.size)
        }

    @Test
    fun `store loads a catalog whose weather topic unions subject and temp`() =
        runBlocking {
            val kinds = FakeKindDao()
            val facets = FakeFacetDao()
            val joins = FakeJoinDao()
            CatalogSeeder(kinds, facets, joins).seed(0L)
            val catalog = CatalogStore(kinds, facets, joins).load()
            val ids = catalog.facetsForTopics(setOf("weather")).map { it.id }.toSet()
            assertTrue("subject" in ids && "temp_c" in ids)
        }

    @Test
    fun `gated catalog hides kinds with no enabled instance`() =
        runBlocking {
            val kinds = FakeKindDao()
            val facets = FakeFacetDao()
            val joins = FakeJoinDao()
            CatalogSeeder(kinds, facets, joins).seed(0L)
            val catalog = CatalogStore(kinds, facets, joins).load()
            val gated = CatalogStore.gated(catalog, enabledKinds = setOf("rss"))
            assertTrue(gated.kindsForTopic("weather").none { it.id == "weather" })
            assertTrue(gated.facetsForTopics(setOf("news")).isNotEmpty())
        }
}
