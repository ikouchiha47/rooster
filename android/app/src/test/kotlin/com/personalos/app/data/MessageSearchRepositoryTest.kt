package com.personalos.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the repository does with a query plan: run the right read, once, with the
 * caller's bound. The plan itself is decided and tested in `core/search`.
 */
class MessageSearchRepositoryTest {
    private class FakeSearchDao : MessageSearchDao {
        var matchExpr: String? = null
        var likePattern: String? = null
        var limit: Int? = null

        override suspend fun searchMatch(
            match: String,
            limit: Int,
        ): List<EventEntity> {
            matchExpr = match
            likePattern = null
            this.limit = limit
            return listOf(row(1))
        }

        override suspend fun searchLike(
            pattern: String,
            limit: Int,
        ): List<EventEntity> {
            likePattern = pattern
            matchExpr = null
            this.limit = limit
            return listOf(row(2))
        }

        private fun row(id: Long) =
            EventEntity(
                id = id,
                ulid = "u$id",
                dedupeKey = "k$id",
                source = "sms",
                type = "inbox",
                timestamp = id,
                title = "HDFCBK",
                content = "Rs 1214 debited",
            )
    }

    @Test
    fun `a long query runs the MATCH read with the caller's limit`() =
        runBlocking {
            val dao = FakeSearchDao()
            val repo = MessageSearchRepository(dao)

            val rows = repo.search("rtel", limit = 50)

            assertEquals("\"rtel\"*", dao.matchExpr)
            assertNull(dao.likePattern)
            assertEquals(50, dao.limit)
            assertEquals(listOf(1L), rows.map { it.id })
        }

    @Test
    fun `a short query runs the LIKE read, never the MATCH read`() =
        runBlocking {
            val dao = FakeSearchDao()
            val repo = MessageSearchRepository(dao)

            val rows = repo.search("ab", limit = 20)

            assertEquals("%ab%", dao.likePattern)
            assertNull(dao.matchExpr)
            assertEquals(20, dao.limit)
            assertEquals(listOf(2L), rows.map { it.id })
        }

    @Test
    fun `a blank query touches the store not at all`() =
        runBlocking {
            val dao = FakeSearchDao()
            val repo = MessageSearchRepository(dao)

            val rows = repo.search("   ", limit = 10)

            assertNull("blank must not run MATCH", dao.matchExpr)
            assertNull("blank must not run LIKE", dao.likePattern)
            assertNull("blank must not reach the store", dao.limit)
            assertEquals(emptyList<EventEntity>(), rows)
        }
}
