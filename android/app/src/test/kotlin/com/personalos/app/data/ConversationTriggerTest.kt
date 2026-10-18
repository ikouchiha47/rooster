package com.personalos.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * The conversations table against a real SQLite with the real v20 DDL.
 *
 * Room skips trigger validation entirely — a trigger is not an entity — so
 * without this test a wrong `SUM`, a missed `source = 'sms'` scope, or a delete
 * that forgets to remove the last row would only ever surface on a device. It
 * runs the same insert / update / delete the app's writers do and checks the
 * counts the screen reads.
 */
class ConversationTriggerTest {
    @Test
    fun `the backfill groups pre-existing sms rows and ignores every other source`() {
        withConversationsDatabase(seedBeforeMigration = true) { db ->
            // Seed: two from ALPHA (inbox + sent) and a one-off from BETA, plus
            // an RSS row whose title is a headline, not a sender.
            val rows = conversations(db)
            assertEquals(listOf("ALPHA", "BETA"), rows.map { it.sender })

            val alpha = rows.first { it.sender == "ALPHA" }
            assertEquals(150L, alpha.lastAt)
            assertEquals(2, alpha.msgCount)
            assertEquals(1, alpha.inboxCount)
            assertEquals(1, alpha.sentCount)
            assertEquals(0, alpha.otherCount)

            val beta = rows.first { it.sender == "BETA" }
            assertEquals(1, beta.msgCount)
            assertEquals(1, beta.inboxCount)
        }
    }

    @Test
    fun `an inserted sms starts a conversation and the next one bumps it`() {
        withConversationsDatabase { db ->
            insert(db, "sms", "inbox", "ALPHA", 100)
            assertEquals(1, conversations(db).single().msgCount)

            insert(db, "sms", "sent", "ALPHA", 200)
            val alpha = conversations(db).single()
            assertEquals("same sender, one row", 1, conversations(db).size)
            assertEquals(2, alpha.msgCount)
            assertEquals(1, alpha.inboxCount)
            assertEquals(1, alpha.sentCount)
            assertEquals(200L, alpha.lastAt)
        }
    }

    @Test
    fun `a non-sms insert is never a conversation`() {
        withConversationsDatabase { db ->
            insert(db, "rss:thehindu-top", "feed", "Metro clears safety trial", 100)
            assertTrue(conversations(db).isEmpty())
        }
    }

    @Test
    fun `deleting the newest message moves the maxima to the next one`() {
        withConversationsDatabase { db ->
            val first = insert(db, "sms", "inbox", "ALPHA", 100)
            val second = insert(db, "sms", "inbox", "ALPHA", 200)

            delete(db, second)

            val alpha = conversations(db).single()
            assertEquals("counts down by one", 1, alpha.msgCount)
            assertEquals("last_at follows the remaining newest", 100L, alpha.lastAt)
            assertEquals("last_message_id follows the remaining newest", first, alpha.lastMessageId)
        }
    }

    @Test
    fun `deleting the last message removes the conversation row`() {
        withConversationsDatabase { db ->
            val only = insert(db, "sms", "inbox", "ALPHA", 100)
            delete(db, only)
            assertTrue("a conversation does not outlive its messages", conversations(db).isEmpty())
        }
    }

    @Test
    fun `an update recomputes the box counts`() {
        withConversationsDatabase { db ->
            val id = insert(db, "sms", "inbox", "ALPHA", 100)
            updateType(db, id, "sent")

            val alpha = conversations(db).single()
            assertEquals(0, alpha.inboxCount)
            assertEquals(1, alpha.sentCount)
        }
    }

    @Test
    fun `an update that changes the sender moves the message between conversations`() {
        withConversationsDatabase { db ->
            val id = insert(db, "sms", "inbox", "ALPHA", 100)
            db.createStatement().executeUpdate("UPDATE events SET title = 'BETA' WHERE id = $id")

            assertEquals(listOf("BETA"), conversations(db).map { it.sender })
            assertNull("ALPHA's row is gone", conversations(db).firstOrNull { it.sender == "ALPHA" })
        }
    }

    @Test
    fun `a recompute never clears a pinned conversation`() {
        withConversationsDatabase { db ->
            val id = insert(db, "sms", "inbox", "ALPHA", 100)
            db.createStatement().executeUpdate("UPDATE conversations SET pinned = 1")

            updateType(db, id, "sent")

            assertEquals("pinned survives a counts recompute", 1, conversations(db).single().pinned)
        }
    }

    @Test
    fun `the list query joins each conversation's newest message as last_content`() {
        withConversationsDatabase { db ->
            insert(db, "sms", "inbox", "ALPHA", 100, content = "first from alpha")
            insert(db, "sms", "sent", "ALPHA", 150, content = "newest from alpha")
            insert(db, "sms", "inbox", "BETA", 120, content = "only from beta")
            insert(db, "rss:thehindu-top", "feed", "Metro clears safety trial", 130, content = "headline, not a message")

            // Recent is newest message first, and the body is that newest one.
            assertEquals(
                listOf("BETA" to "only from beta", "ALPHA" to "newest from alpha"),
                lastMessages(db, Sql.CONVERSATIONS_RECENT, mapOf("mode" to "all", "limit" to 10)),
            )
            // All is sender A -> Z, same joined bodies.
            assertEquals(
                listOf("ALPHA" to "newest from alpha", "BETA" to "only from beta"),
                lastMessages(db, Sql.CONVERSATIONS_ALL, mapOf("mode" to "all", "cursor" to null, "limit" to 10)),
            )
        }
    }

    @Test
    fun `the list query keeps the box filter and the keyset cursor`() {
        withConversationsDatabase { db ->
            insert(db, "sms", "inbox", "ALPHA", 100, content = "a")
            insert(db, "sms", "inbox", "BETA", 120, content = "b")
            insert(db, "sms", "inbox", "CHARLIE", 140, content = "c")

            assertEquals(
                listOf("ALPHA", "BETA", "CHARLIE"),
                lastMessages(db, Sql.CONVERSATIONS_ALL, mapOf("mode" to "all", "cursor" to null, "limit" to 10)).map { it.first },
            )
            assertEquals(
                listOf("BETA", "CHARLIE"),
                lastMessages(db, Sql.CONVERSATIONS_ALL, mapOf("mode" to "all", "cursor" to "ALPHA", "limit" to 10)).map { it.first },
            )
        }
    }

    @Test
    fun `the timeline buckets a conversation by its newest message's category`() {
        withConversationsDatabase { db ->
            // ALPHA's newest message (140) is promotions, so ALPHA is a promotions
            // conversation even though it also has an older personal message.
            insert(db, "sms", "inbox", "ALPHA", 100, content = "old alpha", category = "personal")
            insert(db, "sms", "inbox", "BETA", 120, content = "beta", category = "personal")
            insert(db, "sms", "inbox", "ALPHA", 140, content = "new alpha", category = "promotions")

            val personal = timeline(db, "personal")
            val promotions = timeline(db, "promotions")

            assertEquals(listOf("BETA" to "beta"), personal)
            assertEquals(listOf("ALPHA" to "new alpha"), promotions)
            // Exactly one tab each: the two sets are disjoint and cover everyone.
            assertEquals(setOf("ALPHA", "BETA"), (personal + promotions).map { it.first }.toSet())

            // The badges are the same rule: conversations per latest category.
            assertEquals(mapOf("personal" to 1, "promotions" to 1), countsByCategory(db))
        }
    }

    // ----------------------------------------------------------------- helpers

    /**
     * A minimal `events` carrying only the columns the v20 DDL touches, then the
     * real statements. This keeps the test about the triggers, not the rest of
     * the schema.
     */
    private fun withConversationsDatabase(
        seedBeforeMigration: Boolean = false,
        block: (Connection) -> Unit,
    ) {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            db.createStatement().executeUpdate(
                """
                CREATE TABLE events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    source TEXT NOT NULL,
                    type TEXT NOT NULL,
                    title TEXT NOT NULL,
                    timestamp INTEGER NOT NULL,
                    content TEXT,
                    category TEXT
                )
                """.trimIndent(),
            )
            if (seedBeforeMigration) {
                insert(db, "sms", "inbox", "ALPHA", 100)
                insert(db, "sms", "sent", "ALPHA", 150)
                insert(db, "sms", "inbox", "BETA", 120)
                insert(db, "rss:thehindu-top", "feed", "Metro clears safety trial", 130)
            }
            // The real v20 statements, not a paraphrase of them.
            MIGRATION_19_20_STATEMENTS.forEach { db.createStatement().executeUpdate(it) }
            block(db)
        }
    }

    private fun insert(
        db: Connection,
        source: String,
        type: String,
        title: String,
        timestamp: Long,
        content: String = "",
        category: String? = null,
    ): Long {
        db
            .prepareStatement(
                "INSERT INTO events (source, type, title, timestamp, content, category) VALUES (?, ?, ?, ?, ?, ?)",
            ).use { st ->
                st.setString(1, source)
                st.setString(2, type)
                st.setString(3, title)
                st.setLong(4, timestamp)
                st.setString(5, content)
                if (category == null) st.setNull(6, java.sql.Types.NULL) else st.setString(6, category)
                st.executeUpdate()
            }
        return db.createStatement().executeQuery("SELECT MAX(id) FROM events").use { rs ->
            rs.next()
            rs.getLong(1)
        }
    }

    private fun delete(
        db: Connection,
        id: Long,
    ) {
        db.createStatement().executeUpdate("DELETE FROM events WHERE id = $id")
    }

    private fun updateType(
        db: Connection,
        id: Long,
        type: String,
    ) {
        db.createStatement().executeUpdate("UPDATE events SET type = '$type' WHERE id = $id")
    }

    private fun conversations(db: Connection): List<Row> =
        db
            .createStatement()
            .executeQuery(
                "SELECT source, sender, last_at, last_message_id, msg_count, inbox_count, sent_count, other_count, pinned " +
                    "FROM conversations ORDER BY sender",
            ).use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            Row(
                                source = rs.getString(1),
                                sender = rs.getString(2),
                                lastAt = rs.getLong(3),
                                lastMessageId = rs.getLong(4),
                                msgCount = rs.getInt(5),
                                inboxCount = rs.getInt(6),
                                sentCount = rs.getInt(7),
                                otherCount = rs.getInt(8),
                                pinned = rs.getInt(9),
                            ),
                        )
                    }
                }
            }

    /**
     * Runs a real [Sql] list query and returns `(sender, last_content)`.
     *
     * SQLite's JDBC driver has no named parameters, so each `:name` occurrence
     * becomes a `?` and is bound in order, repeating the value for a name that
     * appears more than once. That makes this a test of the query the app
     * actually runs rather than a paraphrase of it.
     */
    private fun lastMessages(
        db: Connection,
        query: String,
        params: Map<String, Any?>,
    ): List<Pair<String, String?>> {
        val order = Regex(":(\\w+)").findAll(query).map { it.groupValues[1] }.toList()
        val sql = Regex(":(\\w+)").replace(query) { "?" }
        return db.prepareStatement(sql).use { st ->
            order.forEachIndexed { i, name ->
                val value = params[name]
                if (value == null) st.setNull(i + 1, java.sql.Types.NULL) else st.setObject(i + 1, value)
            }
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(rs.getString("sender") to rs.getString("last_content"))
                }
            }
        }
    }

    /** The real Timeline query for one category, as `(sender, last_content)`. */
    private fun timeline(
        db: Connection,
        categoryId: String,
    ): List<Pair<String, String?>> =
        lastMessages(
            db,
            Sql.CONVERSATIONS_TIMELINE,
            mapOf("categoryId" to categoryId, "cursorAt" to null, "cursorSender" to null, "limit" to 10),
        )

    /** The real per-category count query, as a `category -> count` map. */
    private fun countsByCategory(db: Connection): Map<String, Int> =
        db.prepareStatement(Sql.CONVERSATIONS_COUNTS_BY_CATEGORY).use { st ->
            st.executeQuery().use { rs ->
                buildMap {
                    while (rs.next()) put(rs.getString("category"), rs.getInt("count"))
                }
            }
        }

    private data class Row(
        val source: String,
        val sender: String,
        val lastAt: Long,
        val lastMessageId: Long,
        val msgCount: Int,
        val inboxCount: Int,
        val sentCount: Int,
        val otherCount: Int,
        val pinned: Int,
    )
}
