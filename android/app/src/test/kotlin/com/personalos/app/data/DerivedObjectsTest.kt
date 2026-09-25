package com.personalos.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * The objects Room cannot model as entities — the FTS5 table, its sync
 * triggers, and the `conversations` triggers.
 *
 * They are created twice by design: by the migrations that introduced them, and
 * by [AppDatabase]'s open callback. Both paths are needed because **migrations
 * do not run on a fresh install** — Room creates the entities directly — so an
 * object that only lived in a migration was simply missing there. That is
 * exactly how search came to work on every upgraded device and fail on a fresh
 * install.
 *
 * So these tests hold the two paths together: the migration and the callback
 * must describe the same objects, and the callback alone must be enough to
 * produce them on an empty database.
 */
class DerivedObjectsTest {
    private fun objectNames(statements: List<String>): Set<String> {
        val table = Regex("""CREATE\s+(?:VIRTUAL\s+)?TABLE\s+IF\s+NOT\s+EXISTS\s+(\w+)""", RegexOption.IGNORE_CASE)
        val trigger = Regex("""CREATE\s+TRIGGER\s+IF\s+NOT\s+EXISTS\s+(\w+)""", RegexOption.IGNORE_CASE)
        return statements
            .flatMap { statement ->
                table.findAll(statement).map { it.groupValues[1] }.toList() +
                    trigger.findAll(statement).map { it.groupValues[1] }.toList()
            }.toSet()
    }

    @Test
    fun `the migration path and the open callback describe the same objects`() {
        val fromMigrations =
            MIGRATION_STATEMENTS.values
                .flatten()
                .let(::objectNames)
                .filter { it == "events_fts" || it.startsWith("events_fts_") || it.startsWith("conversations_") }
                .toSet()

        assertEquals(
            "a fresh install and an upgraded one must end with the same schema",
            fromMigrations,
            objectNames(DERIVED_OBJECT_STATEMENTS),
        )
    }

    @Test
    fun `every derived statement is idempotent, because it runs on every open`() {
        DERIVED_OBJECT_STATEMENTS.forEach { statement ->
            assertTrue(
                "not idempotent, so opening the database twice would fail: ${statement.take(60)}",
                statement.contains("IF NOT EXISTS", ignoreCase = true),
            )
        }
    }

    /**
     * The fresh-install path: an empty database, the entities' `events` table
     * (the FTS index's external content target), and the callback's statements.
     * Nothing here is a migration.
     */
    @Test
    fun `the open callback alone produces the FTS table and every trigger`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY, title TEXT, content TEXT)")

            // Twice: opening the database twice must be a no-op, not an error.
            repeat(2) { DERIVED_OBJECT_STATEMENTS.forEach { db.execSQL(it) } }

            val objects =
                db.objectTypes(
                    listOf(
                        "events_fts",
                        "events_fts_ai",
                        "events_fts_ad",
                        "events_fts_au",
                        "conversations_ai",
                        "conversations_ad",
                        "conversations_au",
                    ),
                )
            assertEquals("the index", "table", objects["events_fts"])
            assertEquals("insert trigger", "trigger", objects["events_fts_ai"])
            assertEquals("delete trigger", "trigger", objects["events_fts_ad"])
            assertEquals("update trigger", "trigger", objects["events_fts_au"])
            assertEquals("conversation insert", "trigger", objects["conversations_ai"])
            assertEquals("conversation delete", "trigger", objects["conversations_ad"])
            assertEquals("conversation update", "trigger", objects["conversations_au"])
        }
    }

    private fun Connection.objectTypes(names: List<String>): Map<String, String> {
        val quoted = names.joinToString(",") { "'$it'" }
        return createStatement()
            .executeQuery("SELECT name, type FROM sqlite_master WHERE name IN ($quoted)")
            .use { rs -> buildMap { while (rs.next()) put(rs.getString(1), rs.getString(2)) } }
    }

    private fun Connection.execSQL(sql: String) {
        createStatement().use { it.execute(sql) }
    }
}
