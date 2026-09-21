package com.personalos.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Room validates `@Query` SQL at compile time, but **migration SQL is opaque to
 * it** - a bad migration only fails at runtime, on a user's device, after their
 * data is already at risk. This test closes that gap the way `sqlc` does for
 * query files: run the real statements against a real SQLite, then assert the
 * resulting schema is exactly what Room expects.
 *
 * It replays the **whole chain** (v2 upward) using the same statement lists the
 * app ships, and compares tables, columns, affinities, nullability, primary
 * keys, indices and views against the newest exported schema JSON under
 * `app/schemas/`. Add a migration and this test covers it with no changes.
 */
class MigrationSchemaTest {
    private val json = Json { ignoreUnknownKeys = true }

    /** Exported tables. Room does not export a view's columns, only its name. */
    private val tables: Map<String, TableSchema> by lazy {
        exportedDatabase()
            .getValue("entities")
            .jsonArray
            .associate { element ->
                val e = element.jsonObject
                e.getValue("tableName").jsonPrimitive.content to e.toTableSchema()
            }
    }

    /**
     * View name -> the exact SQL Room expects to find in `sqlite_master`.
     *
     * Room compares this text when validating a migration, so a whitespace
     * difference or an extra `IF NOT EXISTS` is a hard failure on the device.
     */
    private val views: Map<String, String> by lazy {
        exportedDatabase()
            .get("views")
            ?.jsonArray
            .orEmpty()
            .associate { element ->
                val v = element.jsonObject
                val name = v.getValue("viewName").jsonPrimitive.content
                val template = v.getValue("createSql").jsonPrimitive.content
                name to template.replace(VIEW_NAME_PLACEHOLDER, name)
            }
    }

    private fun exportedDatabase() =
        json
            .parseToJsonElement(schemaFile().readText())
            .jsonObject
            .getValue("database")
            .jsonObject

    // ------------------------------------------------------------------ tests

    @Test
    fun `the exported schema describes exactly what we expect`() {
        assertEquals(setOf("events", "taggers", "item_tags", "places", "mentions", "parties", "party_sources", "sources", "rules", "item_rules", "item_fields", "kinds", "facets", "kind_facets", "sync_runs", "calendar_dates"), tables.keys)
        assertEquals(setOf("item_tags_current"), views.keys)
    }

    @Test
    fun `the database version equals the newest migration target`() {
        // Keys are target versions, so the newest migratable version is
        // max(keys), not one past it. A migration added without a version bump
        // (or vice versa) makes the declared version drift from its own chain.
        val declaredVersion = exportedDatabase().getValue("version").jsonPrimitive.int
        assertEquals(MIGRATION_STATEMENTS.keys.max(), declaredVersion)
    }

    @Test
    fun `the full migration chain produces exactly the exported schema`() {
        withMigratedV2Database { db ->
            tables.forEach { (name, expected) -> assertTableMatches(db, name, expected) }
        }
    }

    @Test
    fun `the full migration chain creates every view with Room's exact SQL`() {
        withMigratedV2Database { db ->
            val created =
                db
                    .createStatement()
                    .executeQuery("SELECT name, sql FROM sqlite_master WHERE type = 'view'")
                    .use { rs ->
                        buildMap { while (rs.next()) put(rs.getString(1), rs.getString(2)) }
                    }
            assertEquals(views.keys, created.keys)
            views.forEach { (name, expectedSql) ->
                assertEquals("view $name SQL", expectedSql, created.getValue(name))
            }
        }
    }

    @Test
    fun `migration collapses rows the old dedupe key let through twice`() {
        withMigratedV2Database { db ->
            // Two rows for the same article, differing only by `type` - exactly
            // the duplication the old (source, type, timestamp, title) index
            // allowed when `type` was renamed.
            val count =
                db
                    .createStatement()
                    .executeQuery("SELECT COUNT(*) FROM events")
                    .use { it.getInt(1) }
            assertEquals(2, count)
        }
    }

    @Test
    fun `migration backfills a stable key for every legacy row`() {
        withMigratedV2Database { db ->
            db
                .createStatement()
                .executeQuery("SELECT id, ulid, dedupe_key FROM events ORDER BY id")
                .use { rs ->
                    val rows =
                        buildList {
                            while (rs.next()) add(Triple(rs.getLong(1), rs.getString(2), rs.getString(3)))
                        }
                    assertEquals(2, rows.size)
                    rows.forEach { (id, ulid, key) ->
                        assertEquals("legacy-$id", ulid)
                        assertTrue("dedupe_key must not be blank", key.isNotBlank())
                    }
                    // URL wins; without one, source + title.
                    assertEquals("https://x/a", rows[0].third)
                    assertEquals("sms:VM-HDFCBK-S", rows[1].third)
                }
        }
    }

    @Test
    fun `the tags view exposes only the active tagger`() {
        withMigratedV2Database { db ->
            db
                .createStatement()
                .executeUpdate(
                    """
                    INSERT INTO taggers (id, kind, version, active, created_at) VALUES
                      ('heuristic-v1', 'HEURISTIC', 1, 0, 1),
                      ('heuristic-v2', 'HEURISTIC', 2, 1, 2)
                    """.trimIndent(),
                )
            db
                .createStatement()
                .executeUpdate(
                    """
                    INSERT INTO item_tags (item_id, tag, tagger_id, confidence, entity, tagged_at) VALUES
                      ('legacy-1', 'news', 'heuristic-v1', 0.7, NULL, 1),
                      ('legacy-1', 'news', 'heuristic-v2', 0.7, NULL, 2),
                      ('legacy-1', 'personal', 'heuristic-v2', 0.7, NULL, 2)
                    """.trimIndent(),
                )
            val tags =
                db
                    .createStatement()
                    .executeQuery("SELECT tag FROM item_tags_current ORDER BY tag")
                    .use { rs ->
                        buildList { while (rs.next()) add(rs.getString(1)) }
                    }
            // The v1 row must not leak through: without this view, reading
            // item_tags would report `news` twice for the same item.
            assertEquals(listOf("news", "personal"), tags)
        }
    }

    @Test
    fun `v8 to v9 backfills ingested_at from timestamp and adds sources updated_at`() {
        withMigratedV2Database { db ->
            // True ingest time is unknowable for old rows: publish time is the
            // honest fallback, backfilled in the same migration.
            db
                .createStatement()
                .executeQuery("SELECT timestamp, ingested_at FROM events")
                .use { rs ->
                    var rows = 0
                    while (rs.next()) {
                        rows++
                        assertEquals(rs.getLong(1), rs.getLong(2))
                    }
                    assertEquals(2, rows)
                }
            // Nullable: pre-v9 registry rows have no edit to stamp.
            db
                .createStatement()
                .executeQuery("PRAGMA table_info(`sources`)")
                .use { rs ->
                    val columns = buildMap { while (rs.next()) put(rs.getString("name"), rs.getInt("notnull")) }
                    assertTrue("sources.updated_at", columns.containsKey("updated_at"))
                    assertEquals("sources.updated_at is nullable", 0, columns.getValue("updated_at"))
                }
            db
                .createStatement()
                .executeQuery("PRAGMA table_info(`events`)")
                .use { rs ->
                    val columns = buildMap { while (rs.next()) put(rs.getString("name"), rs.getInt("notnull")) }
                    assertTrue("events.ingested_at", columns.containsKey("ingested_at"))
                    assertEquals("events.ingested_at is nullable", 0, columns.getValue("ingested_at"))
                }
        }
    }

    @Test
    fun `v10 to v11 renames rules to sources and creates rules and item_rules`() {
        withMigratedToV10 { db ->
            // The old registry row survives the rename, and so do the columns
            // the rename is meant to carry: `updated_at` and `interval_sec`.
            val sources =
                db
                    .createStatement()
                    .executeQuery("SELECT id, updated_at, interval_sec FROM `sources`")
                    .use { rs ->
                        buildList { while (rs.next()) add(Triple(rs.getString(1), rs.getLong(2), rs.getLong(3))) }
                    }
            assertEquals(listOf(Triple("seed-1", 2L, 3600L)), sources)
            assertEquals(
                "sources columns",
                setOf("id", "name", "kind", "spec_json", "seeded", "enabled", "created_at", "updated_at", "interval_sec"),
                columnsOf(db, "sources").keys,
            )
            assertEquals(
                "rules columns",
                setOf("id", "name", "enabled", "seeded", "condition_json", "action_json", "position", "created_at", "updated_at"),
                columnsOf(db, "rules").keys,
            )
            assertEquals(
                "item_rules columns",
                setOf("item_id", "rule_id", "matched_at"),
                columnsOf(db, "item_rules").keys,
            )
            assertEquals(
                "item_rules indices",
                setOf(listOf("rule_id", "item_id"), listOf("item_id")),
                indexColumnsOf(db, "item_rules"),
            )
        }
    }

    @Test
    fun `v11 to v12 creates item_fields with typed columns, a composite primary key and both indices`() {
        withMigratedToV11 { db ->
            MIGRATION_STATEMENTS.getValue(12).forEach { db.createStatement().executeUpdate(it) }

            assertEquals(
                "item_fields columns",
                setOf("item_id", "name", "value_num", "value_text", "value_flag"),
                columnsOf(db, "item_fields").keys,
            )
            assertEquals(
                "item_fields primary key",
                listOf("item_id", "name"),
                primaryKeyOf(db, "item_fields"),
            )
            // The series-key index for window reads, and the per-item read.
            assertEquals(
                "item_fields indices",
                setOf(listOf("name", "item_id"), listOf("item_id")),
                indexColumnsOf(db, "item_fields"),
            )
            // All three value columns are nullable: exactly one is set per row.
            val notNull = columnsOf(db, "item_fields")
            assertEquals("value_num", 0, notNull.getValue("value_num"))
            assertEquals("value_text", 0, notNull.getValue("value_text"))
            assertEquals("value_flag", 0, notNull.getValue("value_flag"))
        }
    }

    @Test
    fun `v12 to v13 clears seeded rows and purges the dropped Cloudflare source`() {
        // The one spelling of the purged source, derived from the catalog
        // prefix plus the id `status-cloudflare` that used to be in the seeds.
        // Pinned here so a typo in the constant cannot pass its own test.
        assertEquals("rss:status-cloudflare", DROPPED_CLOUDFLARE_SOURCE)

        withMigratedToV12 { db ->
            // Legacy rows exactly as the pre-reconciliation seeders left them:
            // a meaningless ULID id with `seeded = 1`, plus a real user row.
            db.createStatement().executeUpdate(
                """
                INSERT INTO sources (id, name, kind, spec_json, seeded, enabled, created_at, updated_at, interval_sec) VALUES
                  ('01HX-STALE-SEED', 'Stale bundled source', 'rss', '{"url":"https://x"}', 1, 1, 1, 1, NULL),
                  ('seed:rss:thehindu-top', 'The Hindu', 'rss', '{"url":"https://y"}', 1, 1, 1, 1, NULL),
                  ('01HX-USER-SRC', 'My feed', 'rss', '{"url":"https://z"}', 0, 1, 1, 1, 900)
                """.trimIndent(),
            )
            db.createStatement().executeUpdate(
                """
                INSERT INTO rules (id, name, enabled, seeded, condition_json, action_json, position, created_at, updated_at) VALUES
                  ('01HX-STALE-RULE', 'Stale bundled rule', 1, 1, '{"marker":true}', '{"delivery":"push","position":10}', 10, 1, 1),
                  ('seed:rule:bandh-and-strike-watch', 'Bandh', 1, 1, '{"marker":true}', '{"delivery":"push","position":20}', 20, 1, 1),
                  ('01HX-USER-RULE', 'My rule', 1, 0, '{"marker":true}', '{"delivery":"none","position":30}', 30, 1, 1)
                """.trimIndent(),
            )
            // One event from the dropped Cloudflare feed and one from a source
            // that stays, each with a dependent row in every child table.
            db.createStatement().executeUpdate(
                """
                INSERT INTO events (ulid, dedupe_key, source, type, category, timestamp, title, content, entities) VALUES
                  ('cf-1', 'cf-1', 'rss:status-cloudflare', 'feed', 'INCIDENT', 1, 'CF outage', 'body', '[]'),
                  ('other-1', 'other-1', 'rss:thehindu-top', 'feed', 'NEWS', 2, 'Metro', 'body', '[]')
                """.trimIndent(),
            )
            db.createStatement().executeUpdate(
                """
                INSERT INTO item_tags (item_id, tag, tagger_id, confidence, entity, tagged_at) VALUES
                  ('cf-1', 'incident', 'heuristic-v1', 0.9, NULL, 1),
                  ('other-1', 'news', 'heuristic-v1', 0.9, NULL, 2)
                """.trimIndent(),
            )
            db.createStatement().executeUpdate(
                """
                INSERT INTO mentions (item_id, kind, surface, entity_id, confidence, mentioned_at) VALUES
                  ('cf-1', 'place', 'Dublin', NULL, 0.8, 1),
                  ('other-1', 'place', 'Kolkata', NULL, 0.8, 2)
                """.trimIndent(),
            )
            db.createStatement().executeUpdate(
                """
                INSERT INTO item_fields (item_id, name, value_num, value_text, value_flag) VALUES
                  ('cf-1', 'amount', 999.0, NULL, NULL),
                  ('other-1', 'amount', 42.0, NULL, NULL)
                """.trimIndent(),
            )
            // A match to a seeded rule, a match to a user rule on the purged
            // item, and a match to a user rule on the surviving item.
            db.createStatement().executeUpdate(
                """
                INSERT INTO item_rules (item_id, rule_id, matched_at) VALUES
                  ('other-1', 'seed:rule:bandh-and-strike-watch', 1),
                  ('cf-1', '01HX-USER-RULE', 1),
                  ('other-1', '01HX-USER-RULE', 1)
                """.trimIndent(),
            )

            MIGRATION_STATEMENTS.getValue(13).forEach { db.createStatement().executeUpdate(it) }

            // Seed rows gone from both tables; user rows untouched.
            assertEquals(listOf("01HX-USER-SRC"), idsOf(db, "sources"))
            assertEquals(listOf("01HX-USER-RULE"), idsOf(db, "rules"))

            // The Cloudflare event and all four kinds of dependent row are gone.
            assertEquals(listOf("other-1"), idsOf(db, "events", "ulid"))
            assertEquals(listOf("other-1"), idsOf(db, "item_tags", "item_id"))
            assertEquals(listOf("other-1"), idsOf(db, "mentions", "item_id"))
            assertEquals(listOf("other-1"), idsOf(db, "item_fields", "item_id"))

            // Matches to seeded rules are gone; the surviving item's match to
            // the user rule remains. The purged item's match is gone with it.
            val matches =
                db
                    .createStatement()
                    .executeQuery("SELECT item_id || '|' || rule_id FROM item_rules ORDER BY 1")
                    .use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            assertEquals(listOf("other-1|01HX-USER-RULE"), matches)
        }
    }

    @Test
    fun `v13 to v14 adds a nullable color column and keeps existing rules`() {
        withMigratedToV13 { db ->
            // A row exactly as a pre-v14 release left it: no colour exists yet.
            db.createStatement().executeUpdate(
                """
                INSERT INTO rules (id, name, enabled, seeded, condition_json, action_json, position, created_at, updated_at) VALUES
                  ('user-1', 'My rule', 1, 0, '{"marker":true}', '{"delivery":"push","position":10}', 10, 1, 1)
                """.trimIndent(),
            )

            MIGRATION_STATEMENTS.getValue(14).forEach { db.createStatement().executeUpdate(it) }

            val columns = columnsOf(db, "rules")
            assertTrue("rules.color", columns.containsKey("color"))
            // Nullable with no default: the DEFAULT trap Room validates against.
            assertEquals("rules.color is nullable", 0, columns.getValue("color"))

            // The existing row survives the ALTER and takes "no colour chosen".
            db
                .createStatement()
                .executeQuery("SELECT name, color FROM rules WHERE id = 'user-1'")
                .use { rs ->
                    assertTrue(rs.next())
                    assertEquals("My rule", rs.getString(1))
                    assertNull(rs.getString(2))
                }
        }
    }

    @Test
    fun `the full migration chain creates the FTS search table and its sync triggers`() {
        // Room cannot validate any of this: `events_fts` is a virtual table and
        // the triggers are not entities, so neither appears in the exported
        // schema. If this test did not exist, a broken v19 would only fail on a
        // device. The exact-table-set assertion above is unchanged, which is why
        // the FTS table is asserted separately rather than added to it.
        withMigratedV2Database { db ->
            val objects =
                db
                    .createStatement()
                    .executeQuery(
                        "SELECT name, type FROM sqlite_master " +
                            "WHERE name IN ('events_fts', 'events_fts_ai', 'events_fts_ad', 'events_fts_au')",
                    ).use { rs -> buildMap { while (rs.next()) put(rs.getString(1), rs.getString(2)) } }

            assertEquals("events_fts is a table", "table", objects["events_fts"])
            assertEquals("insert trigger", "trigger", objects["events_fts_ai"])
            assertEquals("delete trigger", "trigger", objects["events_fts_ad"])
            assertEquals("update trigger", "trigger", objects["events_fts_au"])
        }
    }

    @Test
    fun `v19 backfills existing rows and the triggers keep the index in sync`() {
        withMigratedV2Database { db ->
            // The seeded SMS body is "Rs 1214 debited"; a trigram MATCH can only
            // find it if `rebuild` indexed rows that predate v19.
            val smsId = scalarLong(db, "SELECT id FROM events WHERE source = 'sms'")
            assertEquals(listOf(smsId), ftsRowIds(db, "\"1214\"*"))

            // INSERT: a new message is searchable immediately.
            db.createStatement().executeUpdate(
                """
                INSERT INTO events (ulid, dedupe_key, source, type, category, timestamp, title, content, entities)
                VALUES ('n1', 'sms:n1', 'sms', 'inbox', 'OTP', 5000, 'AXISBK', 'airtel recharge 4321', '[]')
                """.trimIndent(),
            )
            val n1 = scalarLong(db, "SELECT id FROM events WHERE ulid = 'n1'")
            assertEquals(listOf(n1), ftsRowIds(db, "\"rtel\"*"))

            // UPDATE: the new text is indexed and the old text is dropped, so no
            // stale entry is left behind.
            db.createStatement().executeUpdate("UPDATE events SET content = 'nothing to find' WHERE ulid = 'n1'")
            assertTrue("old term must be gone after update", ftsRowIds(db, "\"rtel\"*").isEmpty())
            assertEquals(listOf(n1), ftsRowIds(db, "\"noth\"*"))

            // DELETE: the index entry goes with the row.
            db.createStatement().executeUpdate("DELETE FROM events WHERE ulid = 'n1'")
            assertTrue("term must be gone after delete", ftsRowIds(db, "\"noth\"*").isEmpty())
        }
    }

    // ----------------------------------------------------------------- helpers

    /** Rowids the FTS index matches for an already-built MATCH expression. */
    private fun ftsRowIds(
        db: Connection,
        expression: String,
    ): List<Long> =
        db.prepareStatement("SELECT rowid FROM events_fts WHERE events_fts MATCH ?").use { st ->
            st.setString(1, expression)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } }
        }

    private fun scalarLong(
        db: Connection,
        sql: String,
    ): Long =
        db.createStatement().executeQuery(sql).use { rs ->
            assertTrue("expected one row from: $sql", rs.next())
            rs.getLong(1)
        }

    /**
     * Replays the chain to v10, inserts one row in the old `rules` registry,
     * then applies v11 — so the rename's data retention can be asserted, which
     * the whole-chain replay (with its empty tables) cannot show.
     */
    private fun withMigratedToV10(block: (Connection) -> Unit) {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            V2_STATEMENTS.forEach { db.createStatement().executeUpdate(it) }
            MIGRATION_STATEMENTS.entries
                .sortedBy { it.key }
                .filter { it.key <= 10 }
                .forEach { (_, statements) -> statements.forEach { db.createStatement().executeUpdate(it) } }
            db
                .createStatement()
                .executeUpdate(
                    """
                    INSERT INTO rules (id, name, kind, spec_json, seeded, enabled, created_at, updated_at, interval_sec)
                    VALUES ('seed-1', 'West Bengal', 'search', '{"query": "West Bengal"}', 1, 1, 1, 2, 3600)
                    """.trimIndent(),
                )
            MIGRATION_STATEMENTS
                .getValue(11)
                .forEach { db.createStatement().executeUpdate(it) }
            block(db)
        }
    }

    /** Replays the chain to v11 only, so the v11 -> v12 statements can be applied alone. */
    private fun withMigratedToV11(block: (Connection) -> Unit) {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            V2_STATEMENTS.forEach { db.createStatement().executeUpdate(it) }
            MIGRATION_STATEMENTS.entries
                .sortedBy { it.key }
                .filter { it.key <= 11 }
                .forEach { (_, statements) -> statements.forEach { db.createStatement().executeUpdate(it) } }
            block(db)
        }
    }

    /** Replays the chain to v12 only, so the v12 -> v13 statements can be applied alone. */
    private fun withMigratedToV12(block: (Connection) -> Unit) {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            V2_STATEMENTS.forEach { db.createStatement().executeUpdate(it) }
            MIGRATION_STATEMENTS.entries
                .sortedBy { it.key }
                .filter { it.key <= 12 }
                .forEach { (_, statements) -> statements.forEach { db.createStatement().executeUpdate(it) } }
            block(db)
        }
    }

    /** Replays the chain to v13 only, so the v13 -> v14 statements can be applied alone. */
    private fun withMigratedToV13(block: (Connection) -> Unit) {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            V2_STATEMENTS.forEach { db.createStatement().executeUpdate(it) }
            MIGRATION_STATEMENTS.entries
                .sortedBy { it.key }
                .filter { it.key <= 13 }
                .forEach { (_, statements) -> statements.forEach { db.createStatement().executeUpdate(it) } }
            block(db)
        }
    }

    /** Distinct values of a column, sorted, so "what survived" reads as one list. */
    private fun idsOf(
        db: Connection,
        table: String,
        column: String = "id",
    ): List<String> =
        db
            .createStatement()
            .executeQuery("SELECT DISTINCT `$column` FROM `$table` ORDER BY 1")
            .use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }

    private fun columnsOf(
        db: Connection,
        table: String,
    ): Map<String, Int> =
        db.createStatement().executeQuery("PRAGMA table_info(`$table`)").use { rs ->
            buildMap { while (rs.next()) put(rs.getString("name"), rs.getInt("notnull")) }
        }

    /** Column names in primary-key order, so a composite key's order is asserted. */
    private fun primaryKeyOf(
        db: Connection,
        table: String,
    ): List<String> {
        val ranked = mutableListOf<Pair<Int, String>>()
        db.createStatement().executeQuery("PRAGMA table_info(`$table`)").use { rs ->
            while (rs.next()) {
                val pk = rs.getInt("pk")
                if (pk > 0) ranked += pk to rs.getString("name")
            }
        }
        return ranked.sortedBy { it.first }.map { it.second }
    }

    private fun indexColumnsOf(
        db: Connection,
        table: String,
    ): Set<List<String>> {
        val names = mutableListOf<String>()
        db.createStatement().executeQuery("PRAGMA index_list(`$table`)").use { rs ->
            while (rs.next()) {
                val name = rs.getString("name")
                if (!name.startsWith("sqlite_autoindex")) names += name
            }
        }
        return names
            .map { name ->
                db.createStatement().executeQuery("PRAGMA index_info(`$name`)").use { rs ->
                    buildList { while (rs.next()) add(rs.getString("name")) }
                }
            }.toSet()
    }

    private fun withMigratedV2Database(block: (Connection) -> Unit) {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            V2_STATEMENTS.forEach { db.createStatement().executeUpdate(it) }
            db
                .createStatement()
                .executeUpdate(
                    """
                    INSERT INTO events (source, type, category, timestamp, title, content, entities, location, url)
                    VALUES
                      ('rss:thehindu-top', 'feed', 'NEWS', 1000, 'Metro clears safety trial', 'body', '[]', NULL, 'https://x/a'),
                      ('rss:thehindu-top', 'news', 'NEWS', 1000, 'Metro clears safety trial', 'body', '[]', NULL, 'https://x/a'),
                      ('sms', 'inbox', 'TRANSACTION', 2000, 'VM-HDFCBK-S', 'Rs 1214 debited', '[]', NULL, NULL)
                    """.trimIndent(),
                )
            // Replay the real chain, oldest first, exactly as Room would.
            MIGRATION_STATEMENTS.entries
                .sortedBy { it.key }
                .forEach { (_, statements) ->
                    statements.forEach { db.createStatement().executeUpdate(it) }
                }
            block(db)
        }
    }

    private fun assertTableMatches(
        db: Connection,
        table: String,
        expected: TableSchema,
    ) {
        val actualColumns = mutableMapOf<String, Column>()
        val pk = mutableListOf<String>()
        db.createStatement().executeQuery("PRAGMA table_info(`$table`)").use { rs ->
            while (rs.next()) {
                actualColumns[rs.getString("name")] =
                    Column(
                        affinity = rs.getString("type").uppercase(),
                        notNull = rs.getInt("notnull") == 1,
                        // A declared DEFAULT survives the migration and then fails
                        // Room's validation on open, so the trap has to be asserted.
                        defaultValue = rs.getString("dflt_value"),
                    )
                if (rs.getInt("pk") > 0) pk += rs.getString("name")
            }
        }
        assertEquals("$table columns", expected.columns.keys, actualColumns.keys)
        expected.columns.forEach { (name, want) ->
            assertEquals("$table.$name", want, actualColumns.getValue(name))
        }
        // A view has no primary key or indices of its own.
        if (expected.primaryKey.isNotEmpty()) {
            assertEquals("$table primary key", expected.primaryKey, pk)
        }

        val indexNames = mutableListOf<String>()
        db.createStatement().executeQuery("PRAGMA index_list(`$table`)").use { rs ->
            while (rs.next()) {
                val name = rs.getString("name")
                if (!name.startsWith("sqlite_autoindex")) indexNames += name
            }
        }
        val actualIndices =
            indexNames
                .map { name ->
                    val cols = mutableListOf<String>()
                    db.createStatement().executeQuery("PRAGMA index_info(`$name`)").use { rs ->
                        while (rs.next()) cols += rs.getString("name")
                    }
                    val unique =
                        db.createStatement().executeQuery("PRAGMA index_list(`$table`)").use { rs ->
                            var u = false
                            while (rs.next()) if (rs.getString("name") == name) u = rs.getInt("unique") == 1
                            u
                        }
                    Index(cols, unique)
                }.toSet()
        assertEquals("$table indices", expected.indices, actualIndices)
    }

    private fun kotlinx.serialization.json.JsonObject.toTableSchema(): TableSchema =
        TableSchema(
            columns =
                getValue("fields").jsonArray.associate { field ->
                    val f = field.jsonObject
                    f.getValue("columnName").jsonPrimitive.content to
                        Column(
                            affinity =
                                f
                                    .getValue("affinity")
                                    .jsonPrimitive.content
                                    .uppercase(),
                            notNull = f["notNull"]?.jsonPrimitive?.booleanOrNull ?: false,
                            defaultValue = f["defaultValue"]?.jsonPrimitive?.contentOrNull,
                        )
                },
            primaryKey =
                getValue("primaryKey")
                    .jsonObject
                    .getValue("columnNames")
                    .jsonArray
                    .map { it.jsonPrimitive.content },
            indices =
                get("indices")
                    ?.jsonArray
                    .orEmpty()
                    .map { index ->
                        val i = index.jsonObject
                        Index(
                            columns = i.getValue("columnNames").jsonArray.map { it.jsonPrimitive.content },
                            unique = i.getValue("unique").jsonPrimitive.booleanOrNull ?: false,
                        )
                    }.toSet(),
        )

    private fun schemaFile(): File {
        val dir =
            generateSequence(File(".").absoluteFile) { it.parentFile }
                .map { File(it, SCHEMA_DIR) }
                .firstOrNull { it.isDirectory }
                ?: error("Exported Room schema dir $SCHEMA_DIR not found - is exportSchema = true and ksp room.schemaLocation set?")

        // Newest exported version, so adding a migration needs no test change.
        return dir
            .listFiles { f -> f.name.endsWith(".json") }
            ?.maxByOrNull { it.nameWithoutExtension.toIntOrNull() ?: 0 }
            ?: error("No schema JSON in $dir")
    }

    private data class Column(
        val affinity: String,
        val notNull: Boolean,
        val defaultValue: String?,
    )

    private data class Index(
        val columns: List<String>,
        val unique: Boolean,
    )

    private data class TableSchema(
        val columns: Map<String, Column>,
        val primaryKey: List<String>,
        val indices: Set<Index>,
    )

    private companion object {
        const val SCHEMA_DIR = "schemas/com.personalos.app.data.AppDatabase"

        /** Room's view template carries this placeholder, unsubstituted. */
        const val VIEW_NAME_PLACEHOLDER = "\${VIEW_NAME}"

        /** Reconstructed v2 shape, before ulid / dedupe_key / tag tables. */
        val V2_STATEMENTS =
            listOf(
                """
                CREATE TABLE IF NOT EXISTS `events` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `source` TEXT NOT NULL,
                    `type` TEXT NOT NULL,
                    `category` TEXT NOT NULL,
                    `timestamp` INTEGER NOT NULL,
                    `title` TEXT NOT NULL,
                    `content` TEXT NOT NULL,
                    `entities` TEXT NOT NULL,
                    `location` TEXT,
                    `url` TEXT
                )
                """.trimIndent(),
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_events_source_type_timestamp_title` ON `events` (`source`, `type`, `timestamp`, `title`)",
                "CREATE INDEX IF NOT EXISTS `index_events_category` ON `events` (`category`)",
                "CREATE INDEX IF NOT EXISTS `index_events_timestamp` ON `events` (`timestamp`)",
            )
    }
}
