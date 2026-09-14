package com.personalos.app.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Versions that predate the exported schema baseline (see `app/schemas/`).
 * Nothing ever shipped on them, so they are the only ones allowed to reset.
 */
val LEGACY_VERSIONS = intArrayOf(1)

/** The natural identity of an ingested item: its URL, else source + title. */
const val NATURAL_KEY = "COALESCE(url, source || ':' || title)"

/**
 * v2 -> v3: introduce stable identity ([EventEntity.ulid]), a real dedupe key
 * ([EventEntity.dedupeKey]) and the tag tables.
 *
 * Written as a table rebuild rather than `ALTER TABLE ADD COLUMN`: adding a
 * column forces a SQLite default to be declared, and Room compares column
 * defaults when it validates the schema on open, so an added column carrying
 * `DEFAULT ''` would fail validation. A rebuild produces exactly the DDL Room
 * expects.
 */
val MIGRATION_2_3_STATEMENTS: List<String> =
    listOf(
        """
        CREATE TABLE IF NOT EXISTS events_new (
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
            url TEXT
        )
        """.trimIndent(),
        """
        INSERT INTO events_new
            (id, ulid, dedupe_key, source, type, category, timestamp, title, content, entities, location, url)
        SELECT
            id,
            'legacy-' || id,
            $NATURAL_KEY,
            source, type, category, timestamp, title, content, entities, location, url
        FROM events
        WHERE id IN (SELECT MIN(id) FROM events GROUP BY $NATURAL_KEY)
        """.trimIndent(),
        "DROP TABLE events",
        "ALTER TABLE events_new RENAME TO events",
        "CREATE UNIQUE INDEX IF NOT EXISTS index_events_dedupe_key ON events (dedupe_key)",
        "CREATE UNIQUE INDEX IF NOT EXISTS index_events_ulid ON events (ulid)",
        "CREATE INDEX IF NOT EXISTS index_events_category ON events (category)",
        "CREATE INDEX IF NOT EXISTS index_events_timestamp ON events (timestamp)",
        """
        CREATE TABLE IF NOT EXISTS taggers (
            id TEXT NOT NULL,
            kind TEXT NOT NULL,
            version INTEGER NOT NULL,
            active INTEGER NOT NULL,
            created_at INTEGER NOT NULL,
            PRIMARY KEY(id)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS item_tags (
            item_id TEXT NOT NULL,
            tag TEXT NOT NULL,
            tagger_id TEXT NOT NULL,
            confidence REAL NOT NULL,
            entity TEXT,
            tagged_at INTEGER NOT NULL,
            PRIMARY KEY(item_id, tag, tagger_id)
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS index_item_tags_tag_item_id ON item_tags (tag, item_id)",
        "CREATE INDEX IF NOT EXISTS index_item_tags_item_id ON item_tags (item_id)",
    )

/**
 * v3 -> v4: add the `item_tags_current` view, so tag reads stop seeing every
 * tagger version at once.
 *
 * Room **compares a view's SQL text** during migration validation, so this must
 * match Room's own generated statement exactly: no `IF NOT EXISTS` (Room does
 * not emit it), and `trim()`-ed to strip the raw string's leading indent. Any
 * difference fails as "Migration didn't properly handle: item_tags_current".
 */
val MIGRATION_3_4_STATEMENTS: List<String> =
    listOf("CREATE VIEW `item_tags_current` AS ${Sql.ITEM_TAGS_CURRENT.trim()}")

/**
 * v4 -> v5: record enrichment attempts on items.
 *
 * `enriched_at` is nullable with **no DEFAULT** on purpose. Room compares column
 * defaults when it validates the schema on open, and `ALTER TABLE ADD COLUMN`
 * carrying a default would leave one behind that Room does not expect - the same
 * trap the v2 -> v3 rebuild was written to avoid.
 *
 * `NULL` = never attempted, `0` = attempted and failed, `>0` = enriched at that time.
 */
val MIGRATION_4_5_STATEMENTS: List<String> =
    listOf("ALTER TABLE events ADD COLUMN enriched_at INTEGER")

/**
 * Every migration, keyed by the version it produces. Keeping the DDL as data
 * (rather than buried inside a `Migration` object) is what lets
 * `MigrationSchemaTest` execute the real statements against a real SQLite and
 * compare the result with the exported Room schema - Room cannot validate
 * migration SQL at compile time.
 */
val MIGRATION_STATEMENTS: Map<Int, List<String>> =
    mapOf(
        3 to MIGRATION_2_3_STATEMENTS,
        4 to MIGRATION_3_4_STATEMENTS,
        5 to MIGRATION_4_5_STATEMENTS,
    )

/** The newest version this build can migrate to. */
val CURRENT_VERSION: Int = MIGRATION_STATEMENTS.keys.max() + 1

val MIGRATIONS: Array<Migration> =
    MIGRATION_STATEMENTS
        .entries
        .sortedBy { it.key }
        .map { (version, statements) -> migration(version - 1, version, statements) }
        .toTypedArray()

private fun migration(
    from: Int,
    to: Int,
    statements: List<String>,
): Migration =
    object : Migration(from, to) {
        override fun migrate(db: SupportSQLiteDatabase) {
            statements.forEach { db.execSQL(it) }
        }
    }
