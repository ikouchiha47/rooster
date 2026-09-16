package com.personalos.app.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Types

/**
 * The preview's SQL is a `const` in [Sql], so it can be run against a real
 * SQLite exactly as Room will bind it. This proves what a fake cannot: the date
 * window actually excludes old rows, the optional filters actually narrow, the
 * scan cap actually caps, and the ordering is newest-first.
 *
 * The schema here is only the columns the query touches. It is not a migration
 * test - Room validates the query against the real entities at compile time, and
 * [MigrationSchemaTest] owns the schema - so a minimal table is enough to pin the
 * statement's semantics.
 */
class RulePreviewSqlTest {
    // ------------------------------------------------------------------ tests

    @Test
    fun `the date window excludes items older than since`() {
        withDatabase { db ->
            insertEvent(db, "old", "sms:bank", 1_000)
            insertEvent(db, "new", "sms:bank", 5_000)

            assertEquals(listOf("new"), candidates(db, since = 2_000))
        }
    }

    @Test
    fun `candidates are newest first`() {
        withDatabase { db ->
            insertEvent(db, "a", "src", 1_000)
            insertEvent(db, "b", "src", 3_000)
            insertEvent(db, "c", "src", 2_000)

            assertEquals(listOf("b", "c", "a"), candidates(db, since = 0, limit = 10))
        }
    }

    @Test
    fun `a source filter finds an item the scan cap would otherwise miss`() {
        withDatabase { db ->
            // Five newer items from another source, and one older item from the
            // source the rule asks for.
            (1..5).forEach { insertEvent(db, "other-$it", "rss:other", 10_000L + it) }
            insertEvent(db, "bank", "sms:bank", 1_000)

            // Without narrowing the cap stops before the matching item exists.
            val unNarrowed = candidates(db, since = 0, limit = 3)
            assertEquals(listOf("other-5", "other-4", "other-3"), unNarrowed)
            assertEquals(false, unNarrowed.contains("bank"))

            // Narrowing on the indexed source reaches it.
            assertEquals(listOf("bank"), candidates(db, since = 0, sourceId = "sms:bank", limit = 3))
        }
    }

    @Test
    fun `a tag filter reads the active tagger's view`() {
        withDatabase { db ->
            db.createStatement().executeUpdate("INSERT INTO taggers (id, active) VALUES ('t1', 1), ('t2', 0)")
            insertEvent(db, "tagged", "src", 1_000)
            insertEvent(db, "newer-untagged", "src", 5_000)
            // The older tagger's row must not narrow the query: the view is the
            // active tagger's only, or a stale tag would leak in.
            db.createStatement().executeUpdate(
                "INSERT INTO item_tags (item_id, tag, tagger_id, confidence, entity, tagged_at) VALUES " +
                    "('tagged', 'travel', 't1', 0.9, NULL, 1), " +
                    "('tagged', 'travel', 't2', 0.9, NULL, 2), " +
                    "('newer-untagged', 'travel', 't2', 0.9, NULL, 2)",
            )

            assertEquals(listOf("tagged"), candidates(db, since = 0, tag = "travel", limit = 10))
        }
    }

    @Test
    fun `a mention filter reads the indexed kind and surface pair`() {
        withDatabase { db ->
            insertEvent(db, "mentioned", "src", 1_000)
            insertEvent(db, "newer-other", "src", 5_000)
            db.createStatement().executeUpdate(
                "INSERT INTO mentions (item_id, kind, surface, entity_id, confidence, mentioned_at) VALUES " +
                    "('mentioned', 'place', 'Kolkata', NULL, 0.8, 1), " +
                    "('newer-other', 'place', 'Dublin', NULL, 0.8, 2)",
            )

            assertEquals(
                listOf("mentioned"),
                candidates(db, since = 0, mentionKind = "place", mentionValue = "Kolkata", limit = 10),
            )
        }
    }

    @Test
    fun `null filters leave every dimension unbounded`() {
        withDatabase { db ->
            insertEvent(db, "a", "src-1", 1_000)
            insertEvent(db, "b", "src-2", 2_000)

            assertEquals(listOf("b", "a"), candidates(db, since = 0, limit = 10))
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun insertEvent(
        db: Connection,
        ulid: String,
        source: String,
        timestamp: Long,
    ) {
        db
            .prepareStatement(
                "INSERT INTO events (ulid, source, title, content, timestamp) VALUES (?, ?, ?, ?, ?)",
            ).use { st ->
                st.setString(1, ulid)
                st.setString(2, source)
                st.setString(3, "title-$ulid")
                st.setString(4, "content-$ulid")
                st.setLong(5, timestamp)
                st.executeUpdate()
            }
    }

    /** Runs the real const with the parameters [EventDao.rulePreviewCandidates] binds. */
    private fun candidates(
        db: Connection,
        since: Long,
        sourceId: String? = null,
        tag: String? = null,
        mentionKind: String? = null,
        mentionValue: String? = null,
        limit: Int = 50,
    ): List<String> =
        db.prepareStatement(Sql.EVENTS_RULE_PREVIEW_CANDIDATES).use { st ->
            st.setLong(1, since)
            st.setNullableString(2, sourceId)
            st.setNullableString(3, tag)
            st.setNullableString(4, mentionKind)
            st.setNullableString(5, mentionValue)
            st.setInt(6, limit)
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getString("item_id")) }
            }
        }

    private fun java.sql.PreparedStatement.setNullableString(
        index: Int,
        value: String?,
    ) {
        if (value == null) setNull(index, Types.VARCHAR) else setString(index, value)
    }

    private fun withDatabase(block: (Connection) -> Unit) {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            db.createStatement().executeUpdate(
                """
                CREATE TABLE events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    ulid TEXT NOT NULL,
                    source TEXT NOT NULL,
                    title TEXT NOT NULL,
                    content TEXT NOT NULL,
                    timestamp INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.createStatement().executeUpdate("CREATE INDEX events_timestamp ON events (timestamp)")
            db.createStatement().executeUpdate("CREATE TABLE taggers (id TEXT NOT NULL PRIMARY KEY, active INTEGER NOT NULL)")
            db.createStatement().executeUpdate(
                """
                CREATE TABLE item_tags (
                    item_id TEXT NOT NULL,
                    tag TEXT NOT NULL,
                    tagger_id TEXT NOT NULL,
                    confidence REAL NOT NULL,
                    entity TEXT,
                    tagged_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            // The real view's SQL, so the tag filter is tested against the same
            // active-tagger rule production uses.
            db.createStatement().executeUpdate("CREATE VIEW item_tags_current AS ${Sql.ITEM_TAGS_CURRENT.trim()}")
            db.createStatement().executeUpdate(
                """
                CREATE TABLE mentions (
                    item_id TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    surface TEXT NOT NULL,
                    entity_id TEXT,
                    confidence REAL NOT NULL,
                    mentioned_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            block(db)
        }
    }
}
