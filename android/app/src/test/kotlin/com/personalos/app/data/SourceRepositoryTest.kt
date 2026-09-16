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
class SourceRepositoryTest {
    private class FakeSourceDao(
        val rows: MutableList<SourceEntity> = mutableListOf(),
    ) : SourceDao {
        override fun observeAll(): Flow<List<SourceEntity>> = flowOf(rows.toList())

        override suspend fun all(): List<SourceEntity> = rows.toList()

        override suspend fun enabled(): List<SourceEntity> = rows.filter { it.enabled }

        override suspend fun count(): Int = rows.size

        override suspend fun insertAll(sources: List<SourceEntity>): List<Long> =
            sources.map { source ->
                if (rows.none { it.id == source.id }) {
                    rows += source
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

    private fun dao() =
        FakeSourceDao(
            mutableListOf(
                SourceEntity("seed-1", "West Bengal", "search", SEARCH_SPEC, true, true, 1L),
                SourceEntity("user-1", "Mine", "rss", RSS_SPEC, false, true, 1L),
            ),
        )

    @Test
    fun `disabling a locked seed is rejected and leaves it enabled`() =
        runBlocking {
            val dao = dao()
            assertThrows(IllegalStateException::class.java) {
                runBlocking { SourceRepository(dao).setEnabled("seed-1", false) }
            }
            assertTrue(dao.rows.first { it.id == "seed-1" }.enabled)
        }

    @Test
    fun `deleting a locked seed is rejected and keeps the row`() =
        runBlocking {
            val dao = dao()
            assertThrows(IllegalStateException::class.java) {
                runBlocking { SourceRepository(dao).delete("seed-1") }
            }
            assertEquals(2, dao.rows.size)
        }

    @Test
    fun `a user rule disables and deletes`() =
        runBlocking {
            val dao = dao()
            val repository = SourceRepository(dao)
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
                runBlocking { SourceRepository(dao).setEnabled("missing", false) }
            }
            assertThrows(IllegalStateException::class.java) {
                runBlocking { SourceRepository(dao).delete("missing") }
            }
        }
    }

    @Test
    fun `adding a user rule validates the spec before insert`() =
        runBlocking {
            val dao = dao()
            val repository = SourceRepository(dao)

            val row = repository.addUserSource("Express", "rss", RSS_SPEC, now = 2L)
            assertEquals(false, row.seeded)
            assertEquals(true, row.enabled)
            assertEquals(3, dao.rows.size)

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.addUserSource("Bad", "rss", """{"tags": ["news"]}""") }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.addUserSource("  ", "rss", RSS_SPEC) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.addUserSource("Future", "scrape", """{"url": "https://x"}""") }
            }
            assertEquals("nothing invalid was stored", 3, dao.rows.size)
        }

    @Test
    fun `disabling a user rule stamps updated_at`() =
        runBlocking {
            val dao = dao()
            SourceRepository(dao).setEnabled("user-1", false, now = 9L)
            assertEquals(9L, dao.rows.first { it.id == "user-1" }.updatedAt)
        }

    @Test
    fun `adding a user rule writes both stamps`() =
        runBlocking {
            val dao = dao()
            val row = SourceRepository(dao).addUserSource("Express", "rss", RSS_SPEC, now = 2L)
            assertEquals(2L, row.createdAt)
            assertEquals(2L, row.updatedAt)
        }

    private companion object {
        const val SEARCH_SPEC =
            """{"query": "West Bengal", "query_lang_code": "en", "source_locale": "en-IN", "tags": ["news"]}"""
        const val RSS_SPEC = """{"url": "https://example.com/feed.xml", "tags": ["news"]}"""
    }
}
