package com.personalos.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Removing the app's stored copy of an item.
 *
 * Two things matter beyond "it deletes": the sidecars go **before** the event
 * row (the same order the schema migrations use), so nothing is ever orphaned
 * by a partial failure; and the answer says whether an event actually went, so
 * a caller can tell a real removal from a no-op.
 */
class MessageDeleteRepositoryTest {
    private class RecordingDao(
        private val eventExists: Boolean = true,
    ) : MessageDeleteDao {
        val calls = mutableListOf<String>()

        override suspend fun deleteTags(itemId: String): Int {
            calls += "tags"
            return 1
        }

        override suspend fun deleteMentions(itemId: String): Int {
            calls += "mentions"
            return 1
        }

        override suspend fun deleteFields(itemId: String): Int {
            calls += "fields"
            return 1
        }

        override suspend fun deleteMatches(itemId: String): Int {
            calls += "matches"
            return 1
        }

        override suspend fun deleteEvent(ulid: String): Int {
            calls += "event"
            return if (eventExists) 1 else 0
        }
    }

    @Test
    fun `every dependent row goes before the event itself`() =
        runBlocking {
            val dao = RecordingDao()
            MessageDeleteRepository(dao).remove("ulid-1")

            assertEquals(
                "sidecars first, event last: a partial failure must not orphan rows",
                listOf("tags", "mentions", "fields", "matches", "event"),
                dao.calls,
            )
        }

    @Test
    fun `removing an item that is not stored reports false and is harmless`() =
        runBlocking {
            val dao = RecordingDao(eventExists = false)
            assertFalse(MessageDeleteRepository(dao).remove("missing"))
            // The sidecar deletes still run: they are idempotent, and skipping
            // them would leave rows behind if an event row had ever gone missing.
            assertTrue(dao.calls.contains("event"))
        }

    @Test
    fun `removing a stored item reports true`() =
        runBlocking {
            assertTrue(MessageDeleteRepository(RecordingDao()).remove("ulid-1"))
        }
}
