package com.personalos.app.data

import com.personalos.app.core.cache.StringCache
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The seed -> DB round-trip: the bundled asset writes `categories` /
 * `category_rules` once, and [CategoryStore] builds the same taxonomy back from
 * the stored rows — so the asset is only the seed and the DB is the store.
 */
class CategoryStoreTest {
    private val asset = File("src/main/assets/category/categorization.v1.json")

    private class FakeCategoryDao : CategoryDao {
        val storedCategories = mutableListOf<CategoryEntity>()
        val storedRules = mutableListOf<CategoryRuleEntity>()

        override suspend fun categories(): List<CategoryEntity> = storedCategories.toList()

        override suspend fun rules(): List<CategoryRuleEntity> = storedRules.toList()

        override suspend fun insertCategories(rows: List<CategoryEntity>): List<Long> =
            rows.map { row ->
                if (storedCategories.any { it.id == row.id }) {
                    -1L
                } else {
                    storedCategories += row
                    1L
                }
            }

        override suspend fun insertRules(rows: List<CategoryRuleEntity>): List<Long> =
            rows.map { row ->
                if (storedRules.any { it.id == row.id }) {
                    -1L
                } else {
                    storedRules += row
                    1L
                }
            }

        override suspend fun upsertCategories(rows: List<CategoryEntity>) {
            rows.forEach { row ->
                storedCategories.removeAll { it.id == row.id }
                storedCategories += row
            }
        }

        override suspend fun upsertRules(rows: List<CategoryRuleEntity>) {
            rows.forEach { row ->
                storedRules.removeAll { it.id == row.id }
                storedRules += row
            }
        }
    }

    private class FakeCache : StringCache {
        private val entries = mutableMapOf<String, StringCache.Entry>()

        override fun read(key: String): StringCache.Entry? = entries[key]

        override fun write(
            key: String,
            value: String,
            at: Long,
        ) {
            entries[key] = StringCache.Entry(value, at)
        }
    }

    private fun store(dao: FakeCategoryDao) = CategoryStore(dao, CategorySeeder(dao, FakeCache()) { asset.inputStream() })

    @Test
    fun `the asset seeds the tables and the store reads the same taxonomy back`() =
        runBlocking {
            val dao = FakeCategoryDao()
            val taxonomy = store(dao).taxonomy()

            assertEquals(
                setOf("personal", "transactional", "promotions", "purchases", "government", "spam", "unknown"),
                taxonomy.categories.map { it.id }.toSet(),
            )
            assertEquals(7, dao.storedCategories.size)
            assertEquals(taxonomy.rules.size, dao.storedRules.size)
            assertTrue("rules are stored as rows", dao.storedRules.isNotEmpty())
            // The rules read back in evaluator order (ascending priority).
            assertEquals(taxonomy.rules.map { it.priority }, taxonomy.rules.map { it.priority }.sorted())
        }

    @Test
    fun `loading twice does not re-seed`() =
        runBlocking {
            val dao = FakeCategoryDao()
            val store = store(dao)
            store.taxonomy()
            val afterFirst = dao.storedCategories.size to dao.storedRules.size

            store.taxonomy()

            assertEquals(afterFirst, dao.storedCategories.size to dao.storedRules.size)
        }

    @Test
    fun `the categorizer built from the store classifies by the stored rules`() =
        runBlocking {
            assertEquals("government", store(FakeCategoryDao()).categorizer().categorize("AX-CPGRMS-G", "", false).id)
        }
}
