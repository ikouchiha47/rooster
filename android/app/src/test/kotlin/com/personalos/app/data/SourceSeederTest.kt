package com.personalos.app.data

import com.personalos.app.core.feed.FeedCatalog
import com.personalos.app.core.sources.RssSpec
import com.personalos.app.core.sources.SearchSpec
import com.personalos.app.core.sources.SourceSpecs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSeederTest {
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

    @Test
    fun `an empty table seeds sixteen locked rows`() =
        runBlocking {
            val dao = FakeSourceDao()
            assertEquals(16, SourceSeeder(dao).seed(now = 1L))
            assertEquals(16, dao.rows.size)
            assertTrue("every seed is locked", dao.rows.all { it.seeded })
            assertTrue("every seed is enabled", dao.rows.all { it.enabled })
            assertEquals(4, dao.rows.count { it.kind == "search" })
            assertEquals(8, dao.rows.count { it.kind == "rss" })
            assertEquals(1, dao.rows.count { it.kind == "sms" })
            assertEquals(1, dao.rows.count { it.kind == "weather" })
            assertEquals(1, dao.rows.count { it.kind == "fx" })
            assertEquals(1, dao.rows.count { it.kind == "device" })
            assertEquals(
                "ids are unique",
                16,
                dao.rows
                    .map { it.id }
                    .toSet()
                    .size,
            )
        }

    @Test
    fun `the search seeds are the four accepted queries`() =
        runBlocking {
            val dao = FakeSourceDao()
            SourceSeeder(dao).seed(now = 1L)

            val queries =
                dao.rows
                    .filter { it.kind == "search" }
                    .map { (SourceSpecs.parse(it.kind, it.specJson) as SearchSpec).query }
                    .toSet()
            assertEquals(setOf("Bangalore", "Kolkata", "Karnataka", "West Bengal"), queries)

            dao.rows.filter { it.kind == "search" }.forEach { row ->
                val spec = SourceSpecs.parse(row.kind, row.specJson) as SearchSpec
                assertEquals(setOf("news"), spec.tags)
                assertEquals("en", spec.queryLangCode)
                assertEquals("en-IN", spec.sourceLocale)
                assertEquals("the source name is its query", spec.query, row.name)
            }
        }

    @Test
    fun `the rss seeds mirror the catalog urls and tags exactly`() =
        runBlocking {
            val dao = FakeSourceDao()
            SourceSeeder(dao).seed(now = 1L)

            val byUrl =
                dao.rows
                    .filter { it.kind == "rss" }
                    .associate { row ->
                        val spec = SourceSpecs.parse(row.kind, row.specJson) as RssSpec
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
            val dao = FakeSourceDao()
            SourceSeeder(dao).seed(now = 7L)
            assertTrue("created_at written", dao.rows.all { it.createdAt == 7L })
            assertTrue("updated_at written", dao.rows.all { it.updatedAt == 7L })
        }

    @Test
    fun `a user-created source is left untouched while every bundled seed is added`() =
        runBlocking {
            val user =
                SourceEntity(
                    id = "user-1",
                    name = "My feed",
                    kind = "rss",
                    specJson = """{"url": "https://example.com/feed", "tags": ["news"]}""",
                    seeded = false,
                    enabled = false,
                    createdAt = 5L,
                    updatedAt = 6L,
                )
            val dao = FakeSourceDao(mutableListOf(user))

            assertEquals(16, SourceSeeder(dao).seed(now = 99L))
            assertEquals(17, dao.rows.size)
            assertEquals("the user's row is byte-identical", user, dao.rows.single { it.id == "user-1" })
        }

    /**
     * An install by an older release shipped fewer sources. The missing rows
     * must be inserted, and the ones already present must not be touched — not
     * even `created_at`, which proves there was no re-write.
     */
    @Test
    fun `a partial install gains the missing sources and keeps the rest byte-identical`() =
        runBlocking {
            val dao = FakeSourceDao()
            SourceSeeder(dao).seed(now = 1L)

            val kept = dao.rows.take(13).toList()
            val missingIds =
                dao.rows
                    .drop(13)
                    .map { it.id }
                    .toSet()
            val keptIds = kept.map { it.id }.toSet()
            dao.rows.retainAll { it.id in keptIds }

            assertEquals(3, SourceSeeder(dao).seed(now = 99L))
            assertEquals(16, dao.rows.size)
            kept.forEach { row -> assertEquals("${row.id} was rewritten", row, dao.rows.single { it.id == row.id }) }
            dao.rows
                .filter { it.id in missingIds }
                .forEach { assertEquals("${it.id} carries the new stamp", 99L, it.createdAt) }
        }

    @Test
    fun `a second run adds nothing`() =
        runBlocking {
            val dao = FakeSourceDao()
            assertEquals(16, SourceSeeder(dao).seed(now = 1L))
            val afterFirst = dao.rows.toList()

            assertEquals(0, SourceSeeder(dao).seed(now = 99L))
            assertEquals("nothing changed on the second run", afterFirst, dao.rows)
        }

    /**
     * Identity is the id, never the spec. A row already stored under a bundled
     * id keeps its own document, so a release that edits a source cannot
     * silently overwrite what is stored.
     */
    @Test
    fun `a differing stored spec under a bundled id is not clobbered`() =
        runBlocking {
            val dao = FakeSourceDao()
            SourceSeeder(dao).seed(now = 1L)

            val target = dao.rows.first()
            dao.rows[0] =
                target.copy(
                    specJson =
                        """{"query": "Elsewhere", "query_lang_code": "en", "source_locale": "en-IN", "tags": ["news"]}""",
                    updatedAt = 5L,
                )
            val stored = dao.rows.toList()

            assertEquals(0, SourceSeeder(dao).seed(now = 99L))
            assertEquals("the stored row wins over the bundled spec", stored, dao.rows)
        }
}
