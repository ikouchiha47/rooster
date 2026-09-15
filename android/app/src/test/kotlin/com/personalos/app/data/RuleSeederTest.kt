package com.personalos.app.data

import com.personalos.app.core.feed.FeedCatalog
import com.personalos.app.core.rules.RssSpec
import com.personalos.app.core.rules.RuleSpecs
import com.personalos.app.core.rules.SearchSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleSeederTest {
    private class FakeRuleDao(
        val rows: MutableList<RuleEntity> = mutableListOf(),
    ) : RuleDao {
        override fun observeAll(): Flow<List<RuleEntity>> = flowOf(rows.toList())

        override suspend fun all(): List<RuleEntity> = rows.toList()

        override suspend fun enabled(): List<RuleEntity> = rows.filter { it.enabled }

        override suspend fun count(): Int = rows.size

        override suspend fun insertAll(rules: List<RuleEntity>): List<Long> =
            rules.map { rule ->
                if (rows.none { it.id == rule.id }) {
                    rows += rule
                    1L
                } else {
                    -1L
                }
            }

        override suspend fun updateEnabled(
            id: String,
            enabled: Boolean,
            updatedAt: Long,
        ): Int {
            val index = rows.indexOfFirst { it.id == id && !it.seeded }
            if (index == -1) return 0
            rows[index] = rows[index].copy(enabled = enabled, updatedAt = updatedAt)
            return 1
        }

        override suspend fun deleteUserOnly(id: String): Int = if (rows.removeIf { it.id == id && !it.seeded }) 1 else 0
    }

    @Test
    fun `an empty table seeds thirteen locked rows`() =
        runBlocking {
            val dao = FakeRuleDao()
            assertEquals(13, RuleSeeder(dao).seed(now = 1L))
            assertEquals(13, dao.rows.size)
            assertTrue("every seed is locked", dao.rows.all { it.seeded })
            assertTrue("every seed is enabled", dao.rows.all { it.enabled })
            assertEquals(4, dao.rows.count { it.kind == "search" })
            assertEquals(9, dao.rows.count { it.kind == "rss" })
            assertEquals(
                "ids are unique",
                13,
                dao.rows
                    .map { it.id }
                    .toSet()
                    .size,
            )
        }

    @Test
    fun `the search seeds are the four accepted queries`() =
        runBlocking {
            val dao = FakeRuleDao()
            RuleSeeder(dao).seed(now = 1L)

            val queries =
                dao.rows
                    .filter { it.kind == "search" }
                    .map { (RuleSpecs.parse(it.kind, it.specJson) as SearchSpec).query }
                    .toSet()
            assertEquals(setOf("Bangalore", "Kolkata", "Karnataka", "West Bengal"), queries)

            dao.rows.filter { it.kind == "search" }.forEach { row ->
                val spec = RuleSpecs.parse(row.kind, row.specJson) as SearchSpec
                assertEquals(setOf("news"), spec.tags)
                assertEquals("en", spec.queryLangCode)
                assertEquals("en-IN", spec.sourceLocale)
                assertEquals("the rule name is its query", spec.query, row.name)
            }
        }

    @Test
    fun `the rss seeds mirror the catalog urls and tags exactly`() =
        runBlocking {
            val dao = FakeRuleDao()
            RuleSeeder(dao).seed(now = 1L)

            val byUrl =
                dao.rows
                    .filter { it.kind == "rss" }
                    .associate { row ->
                        val spec = RuleSpecs.parse(row.kind, row.specJson) as RssSpec
                        spec.url to (row to spec.tags)
                    }
            assertEquals(FeedCatalog.SEEDS.map { it.url }.toSet(), byUrl.keys)
            FeedCatalog.SEEDS.forEach { source ->
                val (row, tags) = byUrl.getValue(source.url)
                assertEquals("tags for ${source.id}", source.tags, tags)
                assertEquals("name for ${source.id}", source.name, row.name)
            }
        }

    @Test
    fun `seeds write both stamps`() =
        runBlocking {
            val dao = FakeRuleDao()
            RuleSeeder(dao).seed(now = 7L)
            assertTrue("created_at written", dao.rows.all { it.createdAt == 7L })
            assertTrue("updated_at written", dao.rows.all { it.updatedAt == 7L })
        }

    @Test
    fun `a non-empty table is a no-op`() =
        runBlocking {
            val dao =
                FakeRuleDao(
                    mutableListOf(
                        RuleEntity("id-1", "Mine", "search", """{"query": "x"}""", false, true, 1L),
                    ),
                )
            assertEquals(0, RuleSeeder(dao).seed())
            assertEquals(1, dao.rows.size)
            assertEquals("id-1", dao.rows.single().id)
        }
}
