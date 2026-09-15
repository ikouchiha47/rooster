package com.personalos.app.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * The News day reads run against a real SQLite: day headers group and count
 * per UTC bucket newest-first, and per-day pages stay inside their window on a
 * shared keyset cursor ordered by publish timestamp.
 *
 * Room validates `@Query` SQL at compile time, but these tests prove the
 * runtime shape (windows, cursors, ordering) the compiler cannot see. Named
 * Room parameters become positional `?` in textual order.
 */
class NewsDayQueriesTest {
    private val dayA = 900 * DAY_MS
    private val dayB = 899 * DAY_MS

    @Test
    fun `headers group per day newest first with counts`() {
        withNewsDatabase { db ->
            val headers = dayHeaders(db, "news")
            assertEquals(listOf(dayA to 3, dayB to 2), headers)
        }
    }

    @Test
    fun `headers are tag-scoped`() {
        withNewsDatabase { db ->
            assertEquals(listOf(dayA to 1), dayHeaders(db, "finance"))
        }
    }

    @Test
    fun `a per-day page stays inside its window`() {
        withNewsDatabase { db ->
            val rows = dayPage(db, "news", dayA, dayA + DAY_MS, null, 0L, 10)
            assertEquals(3, rows.size)
            assertEquals(
                "publish order, newest first",
                listOf("a3", "a2", "a1"),
                rows.map { it.event.title },
            )
        }
    }

    @Test
    fun `a per-day cursor pages without gaps or duplicates`() {
        withNewsDatabase { db ->
            val first = dayPage(db, "news", dayA, dayA + DAY_MS, null, 0L, 2)
            assertEquals(listOf("a3", "a2"), first.map { it.event.title })
            val last = first.last().event
            val second = dayPage(db, "news", dayA, dayA + DAY_MS, last.timestamp, last.id, 2)
            assertEquals(listOf("a1"), second.map { it.event.title })
            val tail = second.last().event
            assertEquals(0, dayPage(db, "news", dayA, dayA + DAY_MS, tail.timestamp, tail.id, 2).size)
        }
    }

    @Test
    fun `ordering is publish time, never ingest time`() {
        withNewsDatabase { db ->
            // a2 was ingested in a later sync run than a3 but published
            // earlier: it still sorts under a3.
            val rows = dayPage(db, "news", dayA, dayA + DAY_MS, null, 0L, 10)
            val a2 = rows.first { it.event.title == "a2" }
            val a3 = rows.first { it.event.title == "a3" }
            assertEquals("a3 sorts first", true, rows.indexOf(a3) < rows.indexOf(a2))
            assertEquals("ingest order differs", true, (a2.event.ingestedAt ?: 0L) > (a3.event.ingestedAt ?: 0L))
        }
    }

    @Test
    fun `a timestamp tie breaks on id descending`() {
        withNewsDatabase { db ->
            val rows = dayPage(db, "news", dayB, dayB + DAY_MS, null, 0L, 10)
            assertEquals(listOf("b2", "b1"), rows.map { it.event.title })
        }
    }

    // ----------------------------------------------------------------- helpers

    private fun dayHeaders(
        db: Connection,
        tag: String,
    ): List<Pair<Long, Int>> {
        val sql = Sql.EVENTS_DAY_HEADERS_BY_TAG.replace(":tag", "?")
        db.prepareStatement(sql).use { ps ->
            ps.setString(1, tag)
            ps.executeQuery().use { rs ->
                return buildList {
                    while (rs.next()) add(rs.getLong(1) to rs.getInt(2))
                }
            }
        }
    }

    private fun dayPage(
        db: Connection,
        tag: String,
        dayStart: Long,
        dayEnd: Long,
        cursorTs: Long?,
        cursorId: Long,
        limit: Int,
    ): List<TaggedEvent> {
        // Textual order: tag, dayStart, dayEnd, cursorTs x3, cursorId, limit.
        val sql =
            Sql.EVENTS_BY_TAG_DAY_PAGE
                .replace(":tag", "?")
                .replace(":dayStart", "?")
                .replace(":dayEnd", "?")
                .replace(":cursorTs", "?")
                .replace(":cursorId", "?")
                .replace(":limit", "?")
        db.prepareStatement(sql).use { ps ->
            var i = 1
            ps.setString(i++, tag)
            ps.setLong(i++, dayStart)
            ps.setLong(i++, dayEnd)
            if (cursorTs == null) {
                ps.setNull(i++, java.sql.Types.INTEGER)
            } else {
                ps.setLong(i++, cursorTs)
            }
            if (cursorTs == null) {
                ps.setNull(i++, java.sql.Types.INTEGER)
            } else {
                ps.setLong(i++, cursorTs)
            }
            if (cursorTs == null) {
                ps.setNull(i++, java.sql.Types.INTEGER)
            } else {
                ps.setLong(i++, cursorTs)
            }
            ps.setLong(i++, cursorId)
            ps.setInt(i++, limit)
            ps.executeQuery().use { rs ->
                return buildList {
                    while (rs.next()) {
                        add(
                            TaggedEvent(
                                EventEntity(
                                    id = rs.getLong("id"),
                                    ulid = rs.getString("ulid"),
                                    dedupeKey = rs.getString("dedupe_key"),
                                    source = rs.getString("source"),
                                    type = rs.getString("type"),
                                    category = rs.getString("category"),
                                    timestamp = rs.getLong("timestamp"),
                                    title = rs.getString("title"),
                                    content = rs.getString("content"),
                                    entities = rs.getString("entities"),
                                    enrichedAt = rs.getLong("enriched_at").takeIf { !rs.wasNull() },
                                    ingestedAt = rs.getLong("ingested_at").takeIf { !rs.wasNull() },
                                ),
                                rs.getString("tags"),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun withNewsDatabase(block: (Connection) -> Unit) {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            db.createStatement().executeUpdate(
                """
                CREATE TABLE events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    ulid TEXT NOT NULL,
                    dedupe_key TEXT NOT NULL,
                    source TEXT NOT NULL,
                    type TEXT NOT NULL,
                    category TEXT NOT NULL,
                    timestamp INTEGER NOT NULL,
                    title TEXT NOT NULL,
                    content TEXT NOT NULL,
                    entities TEXT NOT NULL,
                    location TEXT,
                    url TEXT,
                    enriched_at INTEGER,
                    ingested_at INTEGER
                )
                """.trimIndent(),
            )
            db.createStatement().executeUpdate(
                """
                CREATE TABLE taggers (
                    id TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    version INTEGER NOT NULL,
                    active INTEGER NOT NULL,
                    created_at INTEGER NOT NULL,
                    PRIMARY KEY(id)
                )
                """.trimIndent(),
            )
            db.createStatement().executeUpdate(
                """
                CREATE TABLE item_tags (
                    item_id TEXT NOT NULL,
                    tag TEXT NOT NULL,
                    tagger_id TEXT NOT NULL,
                    confidence REAL NOT NULL,
                    entity TEXT,
                    tagged_at INTEGER NOT NULL,
                    PRIMARY KEY(item_id, tag, tagger_id)
                )
                """.trimIndent(),
            )
            db.createStatement().executeUpdate("CREATE VIEW `item_tags_current` AS ${Sql.ITEM_TAGS_CURRENT.trim()}")
            db.createStatement().executeUpdate(
                "INSERT INTO taggers (id, kind, version, active, created_at) VALUES ('heuristic-v2', 'HEURISTIC', 2, 1, 1)",
            )
            // Day A: three news rows; a2 ingested a sync-bucket later than a3
            // despite publishing earlier. Day B: two news rows sharing a
            // timestamp (id tiebreak). One finance row proves tag scoping.
            insert(db, "u-a1", "k-a1", dayA + 1_000L, "a1", dayA + 60_000L, "news")
            insert(db, "u-a2", "k-a2", dayA + 2_000L, "a2", dayA + 3_700_000L, "news")
            insert(db, "u-a3", "k-a3", dayA + 3_000L, "a3", null, "news")
            insert(db, "u-b1", "k-b1", dayB + 1_000L, "b1", dayB + 1_000L, "news")
            insert(db, "u-b2", "k-b2", dayB + 1_000L, "b2", dayB + 1_000L, "news")
            insert(db, "u-f1", "k-f1", dayA + 4_000L, "f1", dayA + 4_000L, "finance")
            block(db)
        }
    }

    private fun insert(
        db: Connection,
        ulid: String,
        key: String,
        timestamp: Long,
        title: String,
        ingestedAt: Long?,
        tag: String,
    ) {
        db
            .prepareStatement(
                "INSERT INTO events (ulid, dedupe_key, source, type, category, timestamp, title, content, entities, ingested_at)" +
                    " VALUES (?, ?, 'rss:x', 'feed', 'NEWS', ?, ?, 'body', '[]', ?)",
            ).use { ps ->
                ps.setString(1, ulid)
                ps.setString(2, key)
                ps.setLong(3, timestamp)
                ps.setString(4, title)
                if (ingestedAt == null) ps.setNull(5, java.sql.Types.INTEGER) else ps.setLong(5, ingestedAt)
                ps.executeUpdate()
            }
        db
            .prepareStatement(
                "INSERT INTO item_tags (item_id, tag, tagger_id, confidence, entity, tagged_at) VALUES (?, ?, 'heuristic-v2', 1.0, NULL, 1)",
            ).use { ps ->
                ps.setString(1, ulid)
                ps.setString(2, tag)
                ps.executeUpdate()
            }
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
