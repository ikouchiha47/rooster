package com.personalos.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The repository is the single owner that keeps bundled seeds add-only:
 * enable/disable/delete on a `seeded = 1` row is rejected, on a user row it
 * works. The fake mirrors the DAO's `seeded = 0` SQL guards, so these tests
 * prove the contract, not just the fake.
 */
class RuleRepositoryTest {
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
        ): Int {
            val index = rows.indexOfFirst { it.id == id && !it.seeded }
            if (index == -1) return 0
            rows[index] = rows[index].copy(enabled = enabled)
            return 1
        }

        override suspend fun deleteUserOnly(id: String): Int = if (rows.removeIf { it.id == id && !it.seeded }) 1 else 0
    }

    private fun dao() =
        FakeRuleDao(
            mutableListOf(
                RuleEntity("seed-1", "West Bengal", "search", SEARCH_SPEC, true, true, 1L),
                RuleEntity("user-1", "Mine", "rss", RSS_SPEC, false, true, 1L),
            ),
        )

    @Test
    fun `disabling a locked seed is rejected and leaves it enabled`() =
        runBlocking {
            val dao = dao()
            assertThrows(IllegalStateException::class.java) {
                runBlocking { RuleRepository(dao).setEnabled("seed-1", false) }
            }
            assertTrue(dao.rows.first { it.id == "seed-1" }.enabled)
        }

    @Test
    fun `deleting a locked seed is rejected and keeps the row`() =
        runBlocking {
            val dao = dao()
            assertThrows(IllegalStateException::class.java) {
                runBlocking { RuleRepository(dao).delete("seed-1") }
            }
            assertEquals(2, dao.rows.size)
        }

    @Test
    fun `a user rule disables and deletes`() =
        runBlocking {
            val dao = dao()
            val repository = RuleRepository(dao)
            repository.setEnabled("user-1", false)
            assertTrue(dao.rows.none { it.enabled && it.id == "user-1" })
            repository.delete("user-1")
            assertEquals(listOf("seed-1"), dao.rows.map { it.id })
        }

    @Test
    fun `an unknown id is rejected`() {
        runBlocking {
            val dao = dao()
            assertThrows(IllegalStateException::class.java) {
                runBlocking { RuleRepository(dao).setEnabled("missing", false) }
            }
            assertThrows(IllegalStateException::class.java) {
                runBlocking { RuleRepository(dao).delete("missing") }
            }
        }
    }

    @Test
    fun `adding a user rule validates the spec before insert`() =
        runBlocking {
            val dao = dao()
            val repository = RuleRepository(dao)

            val row = repository.addUserRule("Express", "rss", RSS_SPEC, now = 2L)
            assertEquals(false, row.seeded)
            assertEquals(true, row.enabled)
            assertEquals(3, dao.rows.size)

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.addUserRule("Bad", "rss", """{"tags": ["news"]}""") }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.addUserRule("  ", "rss", RSS_SPEC) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.addUserRule("Future", "scrape", """{"url": "https://x"}""") }
            }
            assertEquals("nothing invalid was stored", 3, dao.rows.size)
        }

    private companion object {
        const val SEARCH_SPEC =
            """{"query": "West Bengal", "query_lang_code": "en", "source_locale": "en-IN", "tags": ["news"]}"""
        const val RSS_SPEC = """{"url": "https://example.com/feed.xml", "tags": ["news"]}"""
    }
}
