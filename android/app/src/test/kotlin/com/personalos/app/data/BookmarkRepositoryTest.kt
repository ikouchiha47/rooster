package com.personalos.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Saving through the store. The rule itself lives in core and is tested there;
 * this pins what the repository must do around it — read the current state,
 * write the new one, and scope the Saved view.
 */
class BookmarkRepositoryTest {
    private class FakeBookmarkDao : BookmarkDao {
        val rows = mutableMapOf<String, EventEntity>()

        override suspend fun byUlid(ulid: String): EventEntity? = rows[ulid]

        override suspend fun setBookmark(
            ulid: String,
            bookmarkedAt: Long?,
        ): Int {
            val row = rows[ulid] ?: return 0
            rows[ulid] = row.copy(bookmarkedAt = bookmarkedAt)
            return 1
        }

        override fun observeSavedCount(source: String?): Flow<Int> = flowOf(savedNow(source, null, 100).size)

        override suspend fun saved(
            source: String?,
            cursor: Long?,
            limit: Int,
        ): List<EventEntity> = savedNow(source, cursor, limit)

        /** Not suspend, so the Flow count can share one definition with the page read. */
        private fun savedNow(
            source: String?,
            cursor: Long?,
            limit: Int,
        ): List<EventEntity> =
            rows.values
                .filter { (source == null || it.source == source) && it.bookmarkedAt != null }
                .filter { cursor == null || (it.bookmarkedAt ?: 0L) < cursor }
                .sortedByDescending { it.bookmarkedAt }
                .take(limit)
    }

    private fun event(
        ulid: String,
        source: String = "sms",
        bookmarkedAt: Long? = null,
    ) = EventEntity(
        ulid = ulid,
        dedupeKey = "k-$ulid",
        source = source,
        type = "inbox",
        timestamp = 100L,
        title = "HDFCBK",
        content = "Rs 500 debited",
        bookmarkedAt = bookmarkedAt,
    )

    private fun repository(dao: FakeBookmarkDao): BookmarkRepository = BookmarkRepository(dao, now = { 5_000L })

    @Test
    fun `toggling an unsaved message saves it and reports saved`() =
        runBlocking {
            val dao = FakeBookmarkDao().apply { rows["a"] = event("a") }
            val repo = repository(dao)

            assertTrue(repo.toggle("a"))
            assertEquals(5_000L, dao.rows.getValue("a").bookmarkedAt)
        }

    @Test
    fun `toggling a saved message unsaves it and reports unsaved`() =
        runBlocking {
            val dao = FakeBookmarkDao().apply { rows["a"] = event("a", bookmarkedAt = 1L) }
            val repo = repository(dao)

            assertFalse(repo.toggle("a"))
            assertNull(dao.rows.getValue("a").bookmarkedAt)
        }

    @Test
    fun `toggling an item that is not in the store is a no-op, not a crash`() =
        runBlocking {
            val dao = FakeBookmarkDao()
            val repo = repository(dao)

            assertFalse("an item that does not exist is not saved", repo.toggle("missing"))
            assertNull(dao.rows["missing"])
        }

    @Test
    fun `the saved view is scoped when a source is given and open when it is not`() =
        runBlocking {
            val dao =
                FakeBookmarkDao().apply {
                    rows["sms1"] = event("sms1", source = "sms", bookmarkedAt = 30L)
                    rows["rss1"] = event("rss1", source = "rss:thehindu", bookmarkedAt = 20L)
                    rows["plain"] = event("plain", source = "sms")
                }
            val repo = repository(dao)

            assertEquals(listOf("sms1"), repo.saved(source = "sms").map { it.ulid })
            assertEquals(
                "no source means every saved item, newest first",
                listOf("sms1", "rss1"),
                repo.saved(source = null).map { it.ulid },
            )
        }

    @Test
    fun `the saved view pages on its own stamp, not the publish time`() =
        runBlocking {
            val dao =
                FakeBookmarkDao().apply {
                    rows["new"] = event("new", bookmarkedAt = 300L)
                    rows["old"] = event("old", bookmarkedAt = 100L)
                }
            val repo = repository(dao)

            val first = repo.saved(source = null, limit = 1)
            assertEquals(listOf("new"), first.map { it.ulid })
            val second = repo.saved(source = null, cursor = first.single().bookmarkedAt, limit = 1)
            assertEquals(listOf("old"), second.map { it.ulid })
        }
}
