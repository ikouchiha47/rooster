package com.personalos.app.data

import com.personalos.app.core.cache.StringCache
import com.personalos.app.core.category.CategoryTaxonomyJson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The version-gated deploy: a new bundled version rewrites changed rows, a
 * same-version launch is a no-op, and a row the bundle does not name survives.
 */
class CategorySeederTest {
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

    /** A minimal taxonomy; [pattern] lets a test change a rule without a version bump. */
    private fun taxonomyJson(
        version: Int,
        pattern: String = "NEW",
    ) = """
        {
          "id": "categorization",
          "version": $version,
          "unknownId": "unknown",
          "categories": [
            { "id": "spam", "label": "Spam", "sort": 60, "color": "#B71C1C" },
            { "id": "unknown", "label": "Unknown", "sort": 99, "color": "#616161" }
          ],
          "rules": [
            { "id": "spam-threat", "categoryId": "spam", "priority": 31, "matcherType": "body_any", "pattern": "$pattern", "note": "" }
          ]
        }
        """.trimIndent()

    private fun seeder(
        dao: CategoryDao,
        cache: StringCache,
        json: String,
    ) = CategorySeeder(dao, cache) { json.byteInputStream() }

    @Test
    fun `a version bump re-writes a changed bundled rule's pattern`() =
        runBlocking {
            val dao = FakeCategoryDao()
            dao.storedRules += CategoryRuleEntity("spam-threat", "spam", 31, "body_any", "OLD", "")
            val cache = FakeCache()
            cache.write(CategorySeeder.KEY, "1", 0L)

            val seeded = seeder(dao, cache, taxonomyJson(version = 2, pattern = "NEW")).seed()

            assertTrue("the deploy reports the rows it wrote", seeded > 0)
            assertEquals("NEW", dao.storedRules.single { it.id == "spam-threat" }.pattern)
            assertEquals("2", cache.read(CategorySeeder.KEY)?.value)
        }

    @Test
    fun `a same-version launch is an add-only no-op that leaves stored rows untouched`() =
        runBlocking {
            val dao = FakeCategoryDao()
            val cache = FakeCache()
            // First launch deploys v2 and records it.
            seeder(dao, cache, taxonomyJson(version = 2, pattern = "NEW")).seed()

            // A later launch of the same version, even with edited content, does nothing.
            val seeded = seeder(dao, cache, taxonomyJson(version = 2, pattern = "SHOULD NOT APPLY")).seed()

            assertEquals(0, seeded)
            assertEquals("NEW", dao.storedRules.single { it.id == "spam-threat" }.pattern)
        }

    @Test
    fun `a row the bundle does not name survives an upsert`() =
        runBlocking {
            val dao = FakeCategoryDao()
            dao.storedRules += CategoryRuleEntity("my-rule", "personal", 5, "body_any", "MINE", "")
            dao.storedCategories += CategoryEntity("custom", "Custom", 5, "#123456")
            val cache = FakeCache()

            seeder(dao, cache, taxonomyJson(version = 2)).seed()

            assertEquals("MINE", dao.storedRules.single { it.id == "my-rule" }.pattern)
            assertTrue("a non-bundled category is left in place", dao.storedCategories.any { it.id == "custom" })
        }

    @Test
    fun `the bundled asset version is at least 2 so it deploys the rule fixes`() {
        val taxonomy =
            CategoryTaxonomyJson.parse(File("src/main/assets/category/categorization.v1.json").readText())
        assertTrue(taxonomy.version >= 2)
    }
}
