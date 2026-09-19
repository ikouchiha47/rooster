package com.personalos.app.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.personalos.app.core.feed.FeedCatalog

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
 * is enforced at write by `SourceSpecs` instead — same guarantee, identical
 * schemas either way. Column order and affinities mirror `SourceEntity`.
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
 * v10 -> v11: sources and rules split (ADR 0003).
 *
 * The old `rules` table held a source registry, not rules, so it is renamed in
 * place — data untouched, a plain `ALTER TABLE ... RENAME TO`, which is also
 * what carries its `updated_at` and `interval_sec` columns across. `rules` is
 * then free for the real thing, and matches are materialised like tags.
 *
 * New tables only after the rename: no rebuild, no defaults to trip Room's
 * schema validation (see the note above). Column order and affinities mirror
 * `RuleEntity` and `ItemRuleEntity`, and the index names are the ones Room
 * generates for their `Index` annotations.
 */
val MIGRATION_10_11_STATEMENTS: List<String> =
    listOf(
        "ALTER TABLE rules RENAME TO sources",
        """
        CREATE TABLE IF NOT EXISTS rules (
            id TEXT NOT NULL,
            name TEXT NOT NULL,
            enabled INTEGER NOT NULL,
            seeded INTEGER NOT NULL,
            condition_json TEXT NOT NULL,
            action_json TEXT NOT NULL,
            position INTEGER NOT NULL,
            created_at INTEGER NOT NULL,
            updated_at INTEGER,
            PRIMARY KEY(id)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS item_rules (
            item_id TEXT NOT NULL,
            rule_id TEXT NOT NULL,
            matched_at INTEGER NOT NULL,
            PRIMARY KEY(item_id, rule_id)
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS index_item_rules_rule_id_item_id ON item_rules (rule_id, item_id)",
        "CREATE INDEX IF NOT EXISTS index_item_rules_item_id ON item_rules (item_id)",
    )

/**
 * v11 -> v12: typed item fields (ADR 0003 §13).
 *
 * New table only, like v5 -> v6, v6 -> v7 and the post-rename half of v10 ->
 * v11: nothing existing changes, so no rebuild and no data to carry.
 *
 * `item_fields` materialises a canonical item's kind-specific typed extras
 * (`amount`, `sender`, ...) as one row per (item, name), mirroring tags and
 * matches. ADR §13 chose a table over a JSON column on `events` because §8's
 * monitors need a series key the query planner can use: `(name, item_id)` is
 * the window read, `(item_id)` reads one item's extras back for evaluation.
 *
 * The three value columns mirror the language's `FieldValue` (`Num` / `Str` /
 * `Flag`) exactly and are all nullable — exactly one is set. No `DEFAULT`
 * clauses (Room compares defaults when validating on open; see the note
 * above). Column order and affinities mirror `ItemFieldEntity`, and the index
 * names are the ones Room generates for its `Index` annotations.
 */
val MIGRATION_11_12_STATEMENTS: List<String> =
    listOf(
        """
        CREATE TABLE IF NOT EXISTS item_fields (
            item_id TEXT NOT NULL,
            name TEXT NOT NULL,
            value_num REAL,
            value_text TEXT,
            value_flag INTEGER,
            PRIMARY KEY(item_id, name)
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS index_item_fields_name_item_id ON item_fields (name, item_id)",
        "CREATE INDEX IF NOT EXISTS index_item_fields_item_id ON item_fields (item_id)",
    )

/**
 * The `events.source` string of the Cloudflare status feed dropped in plan
 * T8.1: the catalog prefix plus the id (`status-cloudflare`) that used to sit in
 * [FeedCatalog.SEEDS]. Named rather than inlined so there is one spelling of the
 * source being purged, and it is derived from the prefix owner instead of a
 * hand-typed `rss:` literal.
 */
internal const val DROPPED_CLOUDFLARE_SOURCE: String = FeedCatalog.SOURCE_PREFIX + "status-cloudflare"

/**
 * v12 -> v13: drop every seeded row and purge the removed Cloudflare source.
 *
 * ## Why deleting seeded rows on upgrade is lossless
 *
 * Before seed reconciliation landed, [SourceSeeder] and [RuleSeeder] generated a
 * fresh `Ulid.next()` id per install. A device seeded before that change holds
 * those rows under meaningless ULIDs, so the reconcilers' stable ids
 * (`seed:rule:*`, `seed:rss:*`, `seed:search:*`) look missing and the next launch
 * inserts duplicates - nine rules instead of five, twenty-five sources instead of
 * twelve. Matching by id cannot repair this, because the old ids are exactly the
 * part that carries no meaning.
 *
 * A seeded row is un-editable by construction: the repositories' SQL carries
 * `AND seeded = 0` (`Sql.SOURCES_UPDATE_ENABLED`, `Sql.RULES_DELETE_USER_ONLY`,
 * ...), so no user can ever have edited or deleted one, no matter what the UI
 * shows. A seeded row therefore holds nothing but bundled data - and removing
 * it is lossless, because the reconcilers re-insert the bundled set under the
 * stable ids on the next launch. Rows with `seeded = 0` (user sources and user
 * rules) are never touched. **"Delete rows on upgrade" is only safe because of
 * that guard**, which is why the reason travels next to the statements.
 *
 * ## Why the matches go first
 *
 * A match in `item_rules` outlives nothing: its `rule_id` would dangle once the
 * seeded rule is gone. So every match belonging to a seeded rule is deleted
 * before the seeded rules themselves.
 *
 * ## The Cloudflare purge (plan T8.1)
 *
 * `status-cloudflare` was dropped from [FeedCatalog], so it stopped polling, but
 * installs that had it still hold its ingested events and their dependents. The
 * affected source is named by [DROPPED_CLOUDFLARE_SOURCE] alone - deliberately
 * **not** generalised into "delete events whose source is not in the catalog",
 * which would sweep up a user's own sources and any future catalog gap along
 * with it.
 *
 * Dependent rows share `item_id` (`events.ulid`), and are deleted by `item_id`
 * before the events that own them, so nothing is left orphaned. `item_rules`
 * for those items is included: a Cloudflare item may have matched a user rule.
 */
val MIGRATION_12_13_STATEMENTS: List<String> =
    listOf(
        // Matches of seeded rules first: the rules are deleted below, and a
        // match must never outlive the rule it belongs to.
        "DELETE FROM item_rules WHERE rule_id IN (SELECT id FROM rules WHERE seeded = 1)",
        // The dropped source's dependents, by item_id, before the events.
        "DELETE FROM item_tags WHERE item_id IN (SELECT ulid FROM events WHERE source = '$DROPPED_CLOUDFLARE_SOURCE')",
        "DELETE FROM mentions WHERE item_id IN (SELECT ulid FROM events WHERE source = '$DROPPED_CLOUDFLARE_SOURCE')",
        "DELETE FROM item_fields WHERE item_id IN (SELECT ulid FROM events WHERE source = '$DROPPED_CLOUDFLARE_SOURCE')",
        "DELETE FROM item_rules WHERE item_id IN (SELECT ulid FROM events WHERE source = '$DROPPED_CLOUDFLARE_SOURCE')",
        // Now the events themselves.
        "DELETE FROM events WHERE source = '$DROPPED_CLOUDFLARE_SOURCE'",
        // Then the seeded rows: the reconcilers re-insert the bundled set with
        // stable ids on launch. `seeded = 0` rows are untouched.
        "DELETE FROM rules WHERE seeded = 1",
        "DELETE FROM sources WHERE seeded = 1",
    )

/**
 * v13 -> v14: a rule's own colour.
 *
 * Until now the rules list took its colour from the rule's tag, so two rules
 * about the same subject were indistinguishable. `color` makes the colour a
 * property of the rule: an explicit `#RRGGBB`, or `NULL` for "none chosen".
 *
 * A plain `ALTER TABLE ... ADD COLUMN` and **nullable with no DEFAULT**, per the
 * Room-validation rule documented above — an added column carrying a default
 * would leave one behind that Room does not expect, failing validation on open.
 * Existing rows keep `NULL`, which already means the right thing: the reconciler
 * is add-only and never rewrites a stored row, so an install seeded before v14
 * shows no colour until the UI lane chooses one, rather than being silently
 * repainted. No backfill UPDATE.
 */
val MIGRATION_13_14_STATEMENTS: List<String> =
    listOf("ALTER TABLE rules ADD COLUMN color TEXT")

/**
 * v14 -> v15: the facet catalog (ADR 0005 T6).
 *
 * New tables only, like v5 -> v6 and v6 -> v7: nothing existing changes, so no
 * rebuild and no data to carry. No `DEFAULT` clauses (Room compares defaults
 * when validating on open). Column order and affinities mirror
 * `CatalogEntities.kt`, and the index names are the ones Room generates for
 * its `Index` annotations (none here — the primary keys are the lookup path).
 */
val MIGRATION_14_15_STATEMENTS: List<String> =
    listOf(
        """
        CREATE TABLE IF NOT EXISTS kinds (
            id TEXT NOT NULL,
            mode TEXT NOT NULL,
            topics TEXT NOT NULL,
            enrichable INTEGER NOT NULL,
            seeded INTEGER NOT NULL,
            created_at INTEGER NOT NULL,
            PRIMARY KEY(id)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS facets (
            id TEXT NOT NULL,
            value_type TEXT NOT NULL,
            measure TEXT NOT NULL,
            ops TEXT NOT NULL,
            values_from TEXT,
            seeded INTEGER NOT NULL,
            created_at INTEGER NOT NULL,
            PRIMARY KEY(id)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS kind_facets (
            kind_id TEXT NOT NULL,
            facet_id TEXT NOT NULL,
            PRIMARY KEY(kind_id, facet_id)
        )
        """.trimIndent(),
    )

/**
 * v15 -> v16: the sync activity log (Events tab).
 *
 * New table only, like every table migration before it: no rebuild, no
 * defaults, no data to carry. Column order and affinities mirror
 * `SyncRunEntity.kt`; index names are Room's generated
 * `index_sync_runs_<columns>` spellings.
 */
val MIGRATION_15_16_STATEMENTS: List<String> =
    listOf(
        """
        CREATE TABLE IF NOT EXISTS sync_runs (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            source_id TEXT NOT NULL,
            kind TEXT NOT NULL,
            started_at INTEGER NOT NULL,
            finished_at INTEGER NOT NULL,
            ok INTEGER NOT NULL,
            items_added INTEGER NOT NULL,
            error TEXT
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS index_sync_runs_source_id_finished_at ON sync_runs (source_id, finished_at)",
        "CREATE INDEX IF NOT EXISTS index_sync_runs_finished_at ON sync_runs (finished_at)",
    )

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
        11 to MIGRATION_10_11_STATEMENTS,
        12 to MIGRATION_11_12_STATEMENTS,
        13 to MIGRATION_12_13_STATEMENTS,
        14 to MIGRATION_13_14_STATEMENTS,
        15 to MIGRATION_14_15_STATEMENTS,
        16 to MIGRATION_15_16_STATEMENTS,
    )

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
