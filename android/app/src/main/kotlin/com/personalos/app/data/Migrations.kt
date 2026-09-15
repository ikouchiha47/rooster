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
 * v5 -> v6: the mentions layer. New tables only, no rebuild: nothing existing
 * changes, so there is no data to carry over.
 *
 * Column order and affinities mirror the entities in `MentionEntities.kt` -
 * Room validates a migrated database against them on open, and
 * `MigrationSchemaTest` replays these exact statements, so the two must agree.
 */
val MIGRATION_5_6_STATEMENTS: List<String> =
    listOf(
        """
        CREATE TABLE IF NOT EXISTS places (
            id TEXT NOT NULL,
            name TEXT NOT NULL,
            ascii TEXT NOT NULL,
            lat REAL NOT NULL,
            lon REAL NOT NULL,
            fclass TEXT NOT NULL,
            fcode TEXT NOT NULL,
            admin1 TEXT NOT NULL,
            population INTEGER NOT NULL,
            alternates TEXT NOT NULL,
            PRIMARY KEY(id)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS mentions (
            item_id TEXT NOT NULL,
            kind TEXT NOT NULL,
            surface TEXT NOT NULL,
            entity_id TEXT,
            confidence REAL NOT NULL,
            mentioned_at INTEGER NOT NULL,
            PRIMARY KEY(item_id, kind, surface)
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS index_mentions_item_id ON mentions (item_id)",
        "CREATE INDEX IF NOT EXISTS index_mentions_kind_surface ON mentions (kind, surface)",
    )

/**
 * v6 -> v7: the party registry (`parties`) and its sync clocks (`party_sources`).
 *
 * New tables only, like v5 -> v6: no rebuild, no defaults to trip Room's
 * schema validation. `last_sync_at` is nullable (never synced), which needs no
 * default, and `updated_at` is written by every seed and sync.
 */
val MIGRATION_6_7_STATEMENTS: List<String> =
    listOf(
        """
        CREATE TABLE IF NOT EXISTS parties (
            slug TEXT NOT NULL,
            country TEXT NOT NULL,
            name TEXT NOT NULL,
            aliases TEXT NOT NULL,
            stronghold TEXT NOT NULL,
            recognition TEXT NOT NULL,
            updated_at INTEGER NOT NULL,
            PRIMARY KEY(slug)
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS index_parties_country ON parties (country)",
        """
        CREATE TABLE IF NOT EXISTS party_sources (
            country TEXT NOT NULL,
            url TEXT NOT NULL,
            last_sync_at INTEGER,
            PRIMARY KEY(country)
        )
        """.trimIndent(),
    )

/**
 * v7 -> v8: the v1 rules table (ADR 0002).
 *
 * New table only, like v5 -> v6 and v6 -> v7: no rebuild, no data to carry.
 *
 * Two deliberate omissions from the ADR's DDL, both Room-validation traps of
 * the kind documented above: no `DEFAULT` clauses (a declared default would
 * survive in the migrated schema where Room expects none, failing validation
 * on open), and no `CHECK(kind ...)` (Room does not model CHECK, so a migrated
 * database would enforce what a fresh install does not). The kind closed set
 * is enforced at write by `RuleSpecs` instead — same guarantee, identical
 * schemas either way. Column order and affinities mirror `RuleEntity`.
 */
val MIGRATION_7_8_STATEMENTS: List<String> =
    listOf(
        """
        CREATE TABLE IF NOT EXISTS rules (
            id TEXT NOT NULL,
            name TEXT NOT NULL,
            kind TEXT NOT NULL,
            spec_json TEXT NOT NULL,
            seeded INTEGER NOT NULL,
            enabled INTEGER NOT NULL,
            created_at INTEGER NOT NULL,
            PRIMARY KEY(id)
        )
        """.trimIndent(),
    )

/**
 * v8 -> v9: ingest clocks and rule edit clocks.
 *
 * `events.ingested_at` records wall-clock ingest time, so Today's rows can be
 * bucketed by sync run. `rules.updated_at` records the last user edit. Both
 * are nullable with **no DEFAULT**, per the Room-validation rule documented
 * above — an `ADD COLUMN` carrying a default would fail validation on open.
 *
 * True ingest time is unknowable for old rows, so the same migration backfills
 * `ingested_at` from the publish `timestamp`: the honest fallback, and what
 * keeps the `COALESCE(ingested_at, timestamp)` bucket read exact for them.
 */
val MIGRATION_8_9_STATEMENTS: List<String> =
    listOf(
        "ALTER TABLE events ADD COLUMN ingested_at INTEGER",
        "UPDATE events SET ingested_at = timestamp",
        "ALTER TABLE rules ADD COLUMN updated_at INTEGER",
    )

/**
 * v9 -> v10: per-rule re-poll interval, in seconds.
 *
 * Nullable with no DEFAULT (same Room-validation rule as every column before
 * it): null means "follow the shared feed schedule", which is what every
 * existing row keeps. No UPDATE backfill — null already means the right thing.
 */
val MIGRATION_9_10_STATEMENTS: List<String> =
    listOf("ALTER TABLE rules ADD COLUMN interval_sec INTEGER")

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
        6 to MIGRATION_5_6_STATEMENTS,
        7 to MIGRATION_6_7_STATEMENTS,
        8 to MIGRATION_7_8_STATEMENTS,
        9 to MIGRATION_8_9_STATEMENTS,
        10 to MIGRATION_9_10_STATEMENTS,
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
