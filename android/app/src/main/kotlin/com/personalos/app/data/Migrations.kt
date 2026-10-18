package com.personalos.app.data

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL
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
 * v16 -> v17: the calendar (Travel).
 *
 * New table only, like every table migration before it: no rebuild, no
 * defaults, no data to carry. Column order and affinities mirror
 * `CalendarDateEntity.kt`; identity is (source, uid) because a feed's uid is
 * unique only within that feed; index names are Room's generated
 * `index_calendar_dates_<columns>` spellings.
 */
val MIGRATION_16_17_STATEMENTS: List<String> =
    listOf(
        """
        CREATE TABLE IF NOT EXISTS calendar_dates (
            source TEXT NOT NULL,
            feed_uid TEXT NOT NULL,
            region TEXT NOT NULL,
            kind TEXT NOT NULL,
            date TEXT NOT NULL,
            starts_at INTEGER NOT NULL,
            name TEXT NOT NULL,
            fetched_at INTEGER NOT NULL,
            PRIMARY KEY(source, feed_uid)
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS index_calendar_dates_region_starts_at ON calendar_dates (region, starts_at)",
        "CREATE INDEX IF NOT EXISTS index_calendar_dates_starts_at ON calendar_dates (starts_at)",
    )

/**
 * v17 -> v18: saving an item (bookmarks).
 *
 * `bookmarked_at` is a nullable epoch-ms stamp with **no DEFAULT**, per the
 * Room-validation rule documented above: bytes on disk are the same for an
 * absent value and a defaulted one, but Room compares declared defaults when it
 * validates on open, so an added column carrying `DEFAULT 0` would fail there.
 * Null already means the right thing — not saved — so there is no backfill.
 *
 * The index is declared on the entity too. Room compares the index *set*, so an
 * index present only here would fail validation on open exactly like an
 * undeclared default.
 */
val MIGRATION_17_18_STATEMENTS: List<String> =
    listOf(
        "ALTER TABLE events ADD COLUMN bookmarked_at INTEGER",
        "CREATE INDEX IF NOT EXISTS index_events_bookmarked_at ON events (bookmarked_at)",
    )

/**
 * The FTS objects Room cannot model.
 *
 * `events_fts` is an FTS5 external-content virtual table with a custom
 * tokenizer, and its three triggers are ordinary SQL triggers — Room can export
 * neither, so neither is an entity. That has a consequence beyond validation:
 * **migrations do not run on a fresh install** (Room creates the entities
 * directly), so anything created only in a migration is missing there. These
 * statements are therefore both the migration's DDL *and* what
 * [com.personalos.app.data.AppDatabase] installs on open — one definition, two
 * paths, because the alternative is a fresh install with no search index.
 */
internal val FTS_OBJECT_STATEMENTS: List<String> =
    listOf(
        """
        CREATE VIRTUAL TABLE IF NOT EXISTS events_fts USING fts5(
            title,
            content,
            content='events',
            content_rowid='id',
            tokenize='trigram'
        )
        """.trimIndent(),
        // Keep the index in step with the table it indexes.
        """
        CREATE TRIGGER IF NOT EXISTS events_fts_ai AFTER INSERT ON events BEGIN
            INSERT INTO events_fts(rowid, title, content) VALUES (new.id, new.title, new.content);
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS events_fts_ad AFTER DELETE ON events BEGIN
            INSERT INTO events_fts(events_fts, rowid, title, content) VALUES ('delete', old.id, old.title, old.content);
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS events_fts_au AFTER UPDATE ON events BEGIN
            INSERT INTO events_fts(events_fts, rowid, title, content) VALUES ('delete', old.id, old.title, old.content);
            INSERT INTO events_fts(rowid, title, content) VALUES (new.id, new.title, new.content);
        END
        """.trimIndent(),
    )

/**
 * v18 -> v19: Messages search — an FTS5 external-content index over
 * `events(title, content)`, using the **trigram** tokenizer.
 *
 * ## Why FTS5, and why trigram
 *
 * Messages search must match **inside** a word: `rtel` has to find `airtel`,
 * and `654` has to find `9876543210`. A token index cannot do that — it only
 * knows whole tokens and prefixes. FTS5's trigram tokenizer indexes every
 * three-character sequence, so a MATCH finds any sub-string of three or more
 * characters; that is the infix behaviour this feature is for.
 *
 * The platform SQLite has FTS3/FTS4 but **no FTS5**, so the database is opened
 * through `androidx.sqlite:sqlite-bundled` (SQLite 3.50.1, which ships fts5 and
 * the trigram tokenizer) via `AppDatabase.setDriver` — see `build.gradle.kts`.
 *
 * ## Why the table is hand-written here
 *
 * Room cannot model an FTS5 external-content table with a custom tokenizer: it
 * neither exports this table nor validates it on open. It is created by this
 * migration as plain DDL, and `MigrationSchemaTest` is what asserts it exists
 * and stays in sync — Room will not do either.
 *
 * ## External content: an index, not a copy
 *
 * `content='events', content_rowid='id'` makes the FTS table hold only the
 * index; the indexed text is read back from `events` by rowid. So there is no
 * second copy of a message that could drift — "one fact, one owner" holds. The
 * price is that the index must be kept in step by hand, which is what the three
 * triggers do: every INSERT / UPDATE / DELETE of `events` replays into the
 * index. `rebuild` at the end indexes the rows that predate v19.
 *
 * The UPDATE trigger deletes with the **old** values and inserts the **new**
 * ones: the external-content `'delete'` command needs the values that were
 * indexed, so a plain replace would leave stale entries behind.
 *
 * ## Traps this avoids
 *
 * - The trigram tokenizer answers nothing for a MATCH shorter than three
 *   characters. The read path falls back to `LIKE`, and that decision lives in
 *   `core/search` (SmsSearch), never in SQL.
 * - Room compares the index set and declared defaults on open; this migration
 *   adds no column and no ordinary index to `events`, so nothing there moves.
 * - User input never reaches this DDL. The MATCH expression is built and
 *   quoted in `core/search` and bound as a parameter, never concatenated.
 */

val MIGRATION_18_19_STATEMENTS: List<String> =
    FTS_OBJECT_STATEMENTS +
        // Rows that predate the index. `rebuild` re-reads every row from the
        // external content table, which is exactly the backfill needed. Not in
        // [DERIVED_OBJECT_STATEMENTS]: on a fresh install there is nothing to
        // rebuild, and it would run on every open.
        listOf("INSERT INTO events_fts(events_fts) VALUES('rebuild')")

/**
 * v19 -> v20: materialised conversations for the Messages screen.
 *
 * ## Why a table, not a view
 *
 * `GROUP BY events.title` for `source = 'sms'` is what answers "one row per
 * sender". It scans `events`: 2,588 messages today, and the whole grouping takes
 * 0.064s. That is a scan this screen would pay on every open and every arrival,
 * for a shape that changes only when a message lands. So the grouped rows are
 * **materialised** in `conversations`, and read as a plain table.
 *
 * ## Why triggers, not writers
 *
 * The alternative is for every writer (SMS ingest, bookmarking, removal) to
 * call a maintenance method after touching `events`. That is a rule a future
 * writer forgets. Triggers on `events` make the table a consequence of the
 * writes that already happen, so `events` stays the one owner of the message
 * and `conversations` is derived from it, never a second copy.
 *
 * ## `events.title` is the sender only for SMS
 *
 * For `source = 'sms'`, `events.title` is the address as stored
 * (`VA-WBGBNK-S`, `+919830611292`, `1909`). For every other source it is a
 * headline or a place name, so both the backfill and every trigger are scoped
 * `source = 'sms'`. Grouping across sources would turn a headline into a sender.
 *
 * ## Order matters: backfill before triggers
 *
 * The backfill `INSERT ... SELECT` runs **before** the triggers are created.
 * With the triggers already in place, every row the backfill inserted would be
 * replayed through them and counted a second time.
 *
 * ## Update and delete recompute, they do not decrement
 *
 * An `UPDATE` or `DELETE` can make the newest message disappear, so the
 * `last_at` / `last_message_id` maxima must be re-derived from what remains.
 * Both triggers recompute the affected sender's aggregate in full. `UPDATE`
 * recomputes **both** the old and the new `(source, title)` groups, because
 * changing `title` moves a message between conversations. If a conversation has
 * no messages left, its row is deleted — a conversation is not a fact that
 * outlives its messages.
 *
 * `pinned` is deliberately **not** in the recompute's `SET` list: it belongs to
 * the conversation, not to any message, so recomputing counts must not clear it.
 * There is no pin UI in this slice; the column exists only for ordering.
 *
 * ## Traps this avoids
 *
 * - Room cannot model or validate a trigger, so neither the `conversations`
 *   table's triggers nor their behaviour appear in the exported schema. The
 *   `MigrationSchemaTest` asserts they exist, and `ConversationTriggerTest` runs
 *   insert/update/delete against a real SQLite and checks the counts.
 * - The upsert uses `MAX(last_at, excluded.last_at)` / `MAX(last_message_id,
 *   excluded.last_message_id)`: `last_at` is the newest `timestamp` and
 *   `last_message_id` the newest `id`, and they can diverge when an item arrives
 *   carrying an older publish time.
 * - `ON CONFLICT(source, sender)` needs the composite primary key to exist;
 *   it is declared on the entity too, so Room validates it on open.
 * - No `DEFAULT` clauses (Room compares declared defaults); the backfill writes
 *   every column explicitly.
 */
private val CONVERSATION_BASE_STATEMENTS: List<String> =
    listOf(
        """
        CREATE TABLE IF NOT EXISTS conversations (
            source TEXT NOT NULL,
            sender TEXT NOT NULL,
            last_at INTEGER NOT NULL,
            last_message_id INTEGER NOT NULL,
            msg_count INTEGER NOT NULL,
            inbox_count INTEGER NOT NULL,
            sent_count INTEGER NOT NULL,
            other_count INTEGER NOT NULL,
            pinned INTEGER NOT NULL,
            PRIMARY KEY(source, sender)
        )
        """.trimIndent(),
        // Backfill BEFORE the triggers - see the note above.
        """
        INSERT INTO conversations
            (source, sender, last_at, last_message_id, msg_count, inbox_count, sent_count, other_count, pinned)
        SELECT source,
               title,
               MAX(timestamp),
               MAX(id),
               COUNT(*),
               SUM(type = 'inbox'),
               SUM(type = 'sent'),
               SUM(type NOT IN ('inbox', 'sent')),
               0
        FROM events
        WHERE source = 'sms'
        GROUP BY source, title
        """.trimIndent(),
    )

/**
 * The three triggers, kept as their own list so a **fresh install** gets them
 * too. Room creates the schema directly at the current version when there is no
 * database file, so no migration runs and the DDL below would never execute;
 * [ConversationTriggersCallback] installs the same statements on open, idempotently
 * (`IF NOT EXISTS`). One spelling of the triggers, used by both paths.
 */
internal val CONVERSATION_TRIGGER_STATEMENTS: List<String> =
    listOf(
        // INSERT: create the conversation or bump it. The maxima keep both keys
        // correct when events arrive in a different order than they publish.
        """
        CREATE TRIGGER IF NOT EXISTS conversations_ai AFTER INSERT ON events
        WHEN new.source = 'sms'
        BEGIN
            INSERT INTO conversations
                (source, sender, last_at, last_message_id, msg_count, inbox_count, sent_count, other_count, pinned)
            VALUES
                (new.source, new.title, new.timestamp, new.id, 1,
                 CASE WHEN new.type = 'inbox' THEN 1 ELSE 0 END,
                 CASE WHEN new.type = 'sent'  THEN 1 ELSE 0 END,
                 CASE WHEN new.type NOT IN ('inbox', 'sent') THEN 1 ELSE 0 END,
                 0)
            ON CONFLICT(source, sender) DO UPDATE SET
                last_at = MAX(last_at, excluded.last_at),
                last_message_id = MAX(last_message_id, excluded.last_message_id),
                msg_count = msg_count + excluded.msg_count,
                inbox_count = inbox_count + excluded.inbox_count,
                sent_count = sent_count + excluded.sent_count,
                other_count = other_count + excluded.other_count;
        END
        """.trimIndent(),
        // UPDATE: recompute the old and the new (source, title) groups; changing
        // title moves a message between conversations.
        """
        CREATE TRIGGER IF NOT EXISTS conversations_au
        AFTER UPDATE OF source, title, timestamp, type ON events
        WHEN old.source = 'sms' OR new.source = 'sms'
        BEGIN
            DELETE FROM conversations
            WHERE source = old.source AND sender = old.title AND old.source = 'sms'
              AND NOT EXISTS (SELECT 1 FROM events WHERE source = old.source AND title = old.title);

            INSERT INTO conversations
                (source, sender, last_at, last_message_id, msg_count, inbox_count, sent_count, other_count, pinned)
            SELECT events.source, events.title, MAX(events.timestamp), MAX(events.id), COUNT(*),
                   SUM(events.type = 'inbox'), SUM(events.type = 'sent'),
                   SUM(events.type NOT IN ('inbox', 'sent')), 0
            FROM events
            WHERE events.source = old.source AND events.title = old.title AND old.source = 'sms'
            HAVING COUNT(*) > 0
            ON CONFLICT(source, sender) DO UPDATE SET
                last_at = excluded.last_at,
                last_message_id = excluded.last_message_id,
                msg_count = excluded.msg_count,
                inbox_count = excluded.inbox_count,
                sent_count = excluded.sent_count,
                other_count = excluded.other_count;

            DELETE FROM conversations
            WHERE source = new.source AND sender = new.title AND new.source = 'sms'
              AND NOT EXISTS (SELECT 1 FROM events WHERE source = new.source AND title = new.title);

            INSERT INTO conversations
                (source, sender, last_at, last_message_id, msg_count, inbox_count, sent_count, other_count, pinned)
            SELECT events.source, events.title, MAX(events.timestamp), MAX(events.id), COUNT(*),
                   SUM(events.type = 'inbox'), SUM(events.type = 'sent'),
                   SUM(events.type NOT IN ('inbox', 'sent')), 0
            FROM events
            WHERE events.source = new.source AND events.title = new.title AND new.source = 'sms'
            HAVING COUNT(*) > 0
            ON CONFLICT(source, sender) DO UPDATE SET
                last_at = excluded.last_at,
                last_message_id = excluded.last_message_id,
                msg_count = excluded.msg_count,
                inbox_count = excluded.inbox_count,
                sent_count = excluded.sent_count,
                other_count = excluded.other_count;
        END
        """.trimIndent(),
        // DELETE: the last message may be gone, so recompute from what remains -
        // and remove the conversation row entirely when nothing remains.
        """
        CREATE TRIGGER IF NOT EXISTS conversations_ad AFTER DELETE ON events
        WHEN old.source = 'sms'
        BEGIN
            DELETE FROM conversations
            WHERE source = old.source AND sender = old.title
              AND NOT EXISTS (SELECT 1 FROM events WHERE source = old.source AND title = old.title);

            INSERT INTO conversations
                (source, sender, last_at, last_message_id, msg_count, inbox_count, sent_count, other_count, pinned)
            SELECT events.source, events.title, MAX(events.timestamp), MAX(events.id), COUNT(*),
                   SUM(events.type = 'inbox'), SUM(events.type = 'sent'),
                   SUM(events.type NOT IN ('inbox', 'sent')), 0
            FROM events
            WHERE events.source = old.source AND events.title = old.title
            HAVING COUNT(*) > 0
            ON CONFLICT(source, sender) DO UPDATE SET
                last_at = excluded.last_at,
                last_message_id = excluded.last_message_id,
                msg_count = excluded.msg_count,
                inbox_count = excluded.inbox_count,
                sent_count = excluded.sent_count,
                other_count = excluded.other_count;
        END
        """.trimIndent(),
    )

/** Table + backfill + triggers, in that order: the backfill must precede them. */
val MIGRATION_19_20_STATEMENTS: List<String> =
    CONVERSATION_BASE_STATEMENTS + CONVERSATION_TRIGGER_STATEMENTS

/**
 * v20 -> v21: the categorisation taxonomy (`categories`, `category_rules`).
 *
 * New tables only, like v5 -> v6 and v6 -> v7: nothing existing changes, so no
 * rebuild and no data to carry. The tables are **seeded from the bundled asset**
 * at launch by [CategorySeeder] (the same asset -> DB pattern as
 * `places`/`parties`), then read by [CategoryStore] — the asset is the seed, the
 * DB is the runtime store.
 *
 * No `DEFAULT` clauses and no `CHECK`/enums (Room compares declared defaults and
 * does not model CHECK, so a migrated schema must hold exactly what a fresh
 * install creates — the note above). `sort_order`, not `sort`: the DDL is
 * hand-written and the spelling avoids any quoting ambiguity. Column order and
 * affinities mirror `CategoryEntities.kt`; neither table needs an index (seven
 * categories, a handful of rules, read whole).
 */
val MIGRATION_20_21_STATEMENTS: List<String> =
    listOf(
        """
        CREATE TABLE IF NOT EXISTS categories (
            id TEXT NOT NULL,
            label TEXT NOT NULL,
            sort_order INTEGER NOT NULL,
            color TEXT NOT NULL,
            PRIMARY KEY(id)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS category_rules (
            id TEXT NOT NULL,
            category_id TEXT NOT NULL,
            priority INTEGER NOT NULL,
            matcher_type TEXT NOT NULL,
            pattern TEXT NOT NULL,
            note TEXT NOT NULL,
            PRIMARY KEY(id)
        )
        """.trimIndent(),
    )

/**
 * v21 -> v22: the deferred-tag queue ([DeferredTagEntity]).
 *
 * New table only, like every table migration before it: no rebuild, no defaults,
 * no data to carry. The re-tag walk is a one-way cursor, so an item whose body is
 * still pending is stored here instead of being tagged on its headline, and drained
 * once the body lands or the fetch fails. Column order and affinities mirror
 * `DeferredTagEntity`; the primary key is the lookup path, so no index.
 */
val MIGRATION_21_22_STATEMENTS: List<String> =
    listOf(
        """
        CREATE TABLE IF NOT EXISTS deferred_tags (
            item_id TEXT NOT NULL,
            row_id INTEGER NOT NULL,
            enqueued_at INTEGER NOT NULL,
            PRIMARY KEY(item_id)
        )
        """.trimIndent(),
    )

/**
 * Everything that exists in the database but **not as an entity**.
 *
 * Installed on every open, and created by the migrations that introduced each
 * object. A drift test asserts the migration path and this list agree, so
 * upgraded and fresh installs cannot end up with different schemas — which is
 * exactly how search came to be missing on fresh installs while working fine on
 * every upgraded device.
 *
 * Declared after the two lists it joins, because a top-level `val` cannot
 * reference one declared below it.
 */
internal val DERIVED_OBJECT_STATEMENTS: List<String> =
    FTS_OBJECT_STATEMENTS + CONVERSATION_TRIGGER_STATEMENTS

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
        17 to MIGRATION_16_17_STATEMENTS,
        18 to MIGRATION_17_18_STATEMENTS,
        19 to MIGRATION_18_19_STATEMENTS,
        20 to MIGRATION_19_20_STATEMENTS,
        21 to MIGRATION_20_21_STATEMENTS,
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

        /**
         * Required because the database is opened through a supplied driver
         * (`BundledSQLiteDriver`, for FTS5). Room dispatches to this overload
         * when a driver is set, and the base implementation throws
         * `NotImplementedError` — so both paths must run the same statements.
         */
        override fun migrate(connection: SQLiteConnection) {
            statements.forEach { connection.execSQL(it) }
        }
    }
