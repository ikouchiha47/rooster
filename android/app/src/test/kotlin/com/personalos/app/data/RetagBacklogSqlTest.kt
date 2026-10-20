package com.personalos.app.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Types

/**
 * The retag backlog is derived from stored facts (ADR 0010 §3), so its semantics
 * are pinned by running the real [Sql] constants against SQLite — the compiler
 * cannot see the join or the timestamp comparison.
 *
 * The schema below is only the columns the query touches; Room validates the
 * statements against the real entities at build time.
 */
class RetagBacklogSqlTest {
    @Test
    fun `an item with no marker for the active tagger is picked`() {
        withDatabase { db ->
            insertEvent(db, "unseen", "rss:the-hindu", timestamp = 1_000, enrichedAt = null)

            assertEquals(listOf("unseen"), backlog(db, taggerId = "tagger-v2"))
        }
    }

    @Test
    fun `an item enriched after its marker is picked`() {
        withDatabase { db ->
            insertEvent(db, "stale", "rss:the-hindu", timestamp = 1_000, enrichedAt = 2_000)
            marker(db, "stale", "tagger-v2", taggedAt = 1_500)

            assertEquals(listOf("stale"), backlog(db, taggerId = "tagger-v2"))
        }
    }

    @Test
    fun `a fresh marker excludes the item`() {
        withDatabase { db ->
            insertEvent(db, "fresh", "rss:the-hindu", timestamp = 1_000, enrichedAt = 1_000)
            marker(db, "fresh", "tagger-v2", taggedAt = 1_500)

            assertEquals(emptyList<String>(), backlog(db, taggerId = "tagger-v2"))
        }
    }

    @Test
    fun `a marker for another tagger is not the active marker`() {
        withDatabase { db ->
            insertEvent(db, "old-tagger", "rss:the-hindu", timestamp = 1_000, enrichedAt = null)
            marker(db, "old-tagger", "tagger-v1", taggedAt = 1_500)

            assertEquals(listOf("old-tagger"), backlog(db, taggerId = "tagger-v2"))
        }
    }

    @Test
    fun `the backlog is newest first and prefix scoped`() {
        withDatabase { db ->
            insertEvent(db, "sms-new", "sms", timestamp = 5_000, enrichedAt = null)
            insertEvent(db, "feed-new", "rss:the-hindu", timestamp = 9_000, enrichedAt = null)
            insertEvent(db, "sms-old", "sms", timestamp = 1_000, enrichedAt = null)

            assertEquals(
                listOf("sms-new", "sms-old"),
                backlog(db, taggerId = "tagger-v2", prefix = "sms"),
            )
            assertEquals(
                listOf("feed-new", "sms-new", "sms-old"),
                backlog(db, taggerId = "tagger-v2", prefix = null),
            )
        }
    }

    @Test
    fun `the count uses the same predicate without a limit`() {
        withDatabase { db ->
            insertEvent(db, "unseen", "rss:a", timestamp = 1_000, enrichedAt = null)
            insertEvent(db, "stale", "rss:a", timestamp = 2_000, enrichedAt = 2_000)
            insertEvent(db, "fresh", "rss:a", timestamp = 3_000, enrichedAt = 1_000)
            marker(db, "stale", "tagger-v2", taggedAt = 1_500)
            marker(db, "fresh", "tagger-v2", taggedAt = 1_500)

            assertEquals(2, count(db, taggerId = "tagger-v2", prefix = null))
            assertEquals(1, backlog(db, taggerId = "tagger-v2", prefix = null, limit = 1).size)
        }
    }

    @Test
    fun `enrichment candidates stay newest first`() {
        withDatabase { db ->
            insertEvent(db, "old", "rss:a", timestamp = 1_000, enrichedAt = null)
            insertEvent(db, "new", "rss:a", timestamp = 3_000, enrichedAt = null)
            insertEvent(db, "middle", "rss:a", timestamp = 2_000, enrichedAt = null)

            assertEquals(listOf("new", "middle", "old"), enrichmentCandidates(db))
        }
    }

    // ----------------------------------------------------------------- helpers

    private fun backlog(
        db: Connection,
        taggerId: String,
        prefix: String? = null,
        limit: Int = 50,
    ): List<String> =
        db.prepareStatement(Sql.RETAG_BACKLOG).use { ps ->
            ps.setString(1, taggerId)
            ps.setNullableString(2, prefix)
            ps.setInt(3, limit)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getString("ulid")) }
            }
        }

    private fun count(
        db: Connection,
        taggerId: String,
        prefix: String?,
    ): Int =
        db.prepareStatement(Sql.RETAG_BACKLOG_COUNT).use { ps ->
            ps.setString(1, taggerId)
            ps.setNullableString(2, prefix)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }

    private fun enrichmentCandidates(db: Connection): List<String> =
        db.prepareStatement(Sql.ENRICHMENT_CANDIDATES).use { ps ->
            ps.setInt(1, 50)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getString("ulid")) }
            }
        }

    private fun insertEvent(
        db: Connection,
        ulid: String,
        source: String,
        timestamp: Long,
        enrichedAt: Long?,
    ) {
        db
            .prepareStatement(
                """
                INSERT INTO events
                    (ulid, source, category, title, content, url, enriched_at, enrich_attempts, timestamp)
                VALUES (?, ?, 'unknown', ?, ?, ?, ?, 0, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, ulid)
                ps.setString(2, source)
                ps.setString(3, "title-$ulid")
                ps.setString(4, "content-$ulid")
                ps.setString(5, "https://example.com/$ulid")
                if (enrichedAt == null) ps.setNull(6, Types.INTEGER) else ps.setLong(6, enrichedAt)
                ps.setLong(7, timestamp)
                ps.executeUpdate()
            }
    }

    private fun marker(
        db: Connection,
        itemId: String,
        taggerId: String,
        taggedAt: Long,
    ) {
        db
            .prepareStatement("INSERT INTO tagged_items (item_id, tagger_id, tagged_at) VALUES (?, ?, ?)")
            .use { ps ->
                ps.setString(1, itemId)
                ps.setString(2, taggerId)
                ps.setLong(3, taggedAt)
                ps.executeUpdate()
            }
    }

    private fun java.sql.PreparedStatement.setNullableString(
        index: Int,
        value: String?,
    ) {
        if (value == null) {
            setNull(index, Types.VARCHAR)
        } else {
            setString(index, value)
        }
    }

    private fun withDatabase(block: (Connection) -> Unit) {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            db.createStatement().executeUpdate(
                """
                CREATE TABLE events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    ulid TEXT NOT NULL,
                    source TEXT NOT NULL,
                    type TEXT NOT NULL DEFAULT 'feed',
                    category TEXT NOT NULL,
                    timestamp INTEGER NOT NULL,
                    title TEXT NOT NULL,
                    content TEXT NOT NULL,
                    url TEXT,
                    enriched_at INTEGER,
                    enrich_attempts INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            db.createStatement().executeUpdate(
                """
                CREATE TABLE tagged_items (
                    item_id TEXT NOT NULL,
                    tagger_id TEXT NOT NULL,
                    tagged_at INTEGER NOT NULL,
                    PRIMARY KEY(item_id, tagger_id)
                )
                """.trimIndent(),
            )
            block(db)
        }
    }
}
