package com.personalos.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
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
        assertEquals(setOf("events", "taggers", "item_tags", "places", "mentions", "parties", "party_sources"), tables.keys)
        assertEquals(setOf("item_tags_current"), views.keys)
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

    // ----------------------------------------------------------------- helpers

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
                    Column(rs.getString("type").uppercase(), rs.getInt("notnull") == 1)
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
