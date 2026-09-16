package com.personalos.app.data

/**
 * Every SQL statement in one place, so DAOs declare no inline SQL and the whole
 * query surface can be reviewed, indexed, and tuned as a unit.
 *
 * Room resolves these at compile time, so they must stay `const`.
 */
object Sql {
    // ------------------------------------------------------------------ events
    const val EVENTS_COUNT = "SELECT COUNT(*) FROM events"

    const val EVENTS_COUNT_SINCE_TYPE =
        "SELECT COUNT(*) FROM events WHERE type = :type AND timestamp >= :since"

    const val EVENTS_DISTINCT_SOURCES = "SELECT COUNT(DISTINCT source) FROM events"

    const val EVENTS_COUNT_SINCE = "SELECT COUNT(*) FROM events WHERE timestamp >= :since"

    const val EVENTS_DISTINCT_FEED_SOURCES =
        "SELECT COUNT(DISTINCT source) FROM events WHERE source LIKE 'rss:%'"

    const val EVENTS_COUNT_BY_MODE =
        """
        SELECT COUNT(*) FROM events
        WHERE (:mode = 'all'
               OR (:mode = 'inbox' AND type = 'inbox')
               OR (:mode = 'sent'  AND type = 'sent')
               OR (:mode = 'other' AND type NOT IN ('inbox', 'sent')))
        """

    const val EVENTS_MAX_ID = "SELECT MAX(id) FROM events"

    const val EVENTS_PAGE_BY_MODE =
        """
        SELECT * FROM events
        WHERE (:mode = 'all'
               OR (:mode = 'inbox' AND type = 'inbox')
               OR (:mode = 'sent'  AND type = 'sent')
               OR (:mode = 'other' AND type NOT IN ('inbox', 'sent')))
          AND (:cursorTs IS NULL
               OR timestamp < :cursorTs
               OR (timestamp = :cursorTs AND id < :cursorId))
        ORDER BY timestamp DESC, id DESC
        LIMIT :limit
        """

    const val EVENTS_COUNT_BY_CATEGORY =
        "SELECT COUNT(*) FROM events WHERE (:category IS NULL OR category = :category)"

    const val EVENTS_COUNT_BY_SOURCE =
        """
        SELECT source, COUNT(*) AS count FROM events
        GROUP BY source
        ORDER BY count DESC
        """

    const val EVENTS_RADAR_PAGE =
        """
        SELECT * FROM events
        WHERE (:category IS NULL OR category = :category)
          AND (:cursorTs IS NULL
               OR timestamp < :cursorTs
               OR (timestamp = :cursorTs AND id < :cursorId))
        ORDER BY timestamp DESC, id DESC
        LIMIT :limit
        """

    const val EVENTS_DELETE_ALL = "DELETE FROM events"

    /**
     * Tag-scoped reads - the shape every tile uses (docs/ARCHITECTURE.md §11.4).
     *
     * Joined through `item_tags_current`, never `item_tags`: tagging is
     * append-only, so the raw table holds every tagger version's output and a
     * direct join would return each item once per version.
     */
    const val EVENTS_BY_TAG_PAGE =
        """
        SELECT e.*,
               (SELECT GROUP_CONCAT(t2.tag)
                FROM item_tags_current t2
                WHERE t2.item_id = e.ulid) AS tags
        FROM events e
        JOIN item_tags_current t ON t.item_id = e.ulid
        WHERE t.tag = :tag
          AND (:cursorTs IS NULL
               OR e.timestamp < :cursorTs
               OR (e.timestamp = :cursorTs AND e.id < :cursorId))
        ORDER BY e.timestamp DESC, e.id DESC
        LIMIT :limit
        """

    const val EVENTS_COUNT_BY_TAG =
        """
        SELECT COUNT(1) FROM events e
        JOIN item_tags_current t ON t.item_id = e.ulid
        WHERE t.tag = :tag
        """

    /**
     * Day headers for a tag, newest day first: one row per UTC day bucket with
     * its item count. Headers load before any page, so opening a day starts
     * its own cursor and a never-opened day fetches zero rows.
     *
     * The bucket is `timestamp / DAY_MS`, the same bucket
     * [com.personalos.app.ui.common.groupIntoDays] groups by, so a header's
     * `dayStart` matches the rows the per-day page returns. Labels
     * (Today/Yesterday/date) are resolved in the UI from `dayStart`, never
     * here. Ordering stays publish-timestamp; `ingested_at` is only what the
     * Today sync-buckets group by. `COUNT(1)` over the timestamp index: only
     * the count is needed, never the rows.
     */
    const val EVENTS_DAY_HEADERS_BY_TAG =
        """
        SELECT (e.timestamp / 86400000 * 86400000) AS dayStart, COUNT(1) AS count
        FROM events e
        JOIN item_tags_current t ON t.item_id = e.ulid
        WHERE t.tag = :tag
        GROUP BY (e.timestamp / 86400000)
        ORDER BY dayStart DESC
        """

    /**
     * Per-day keyset page of a tag read: the [EVENTS_BY_TAG_PAGE] shape plus a
     * day window, so each day paginates on its own cursor.
     */
    const val EVENTS_BY_TAG_DAY_PAGE =
        """
        SELECT e.*,
               (SELECT GROUP_CONCAT(t2.tag)
                FROM item_tags_current t2
                WHERE t2.item_id = e.ulid) AS tags
        FROM events e
        JOIN item_tags_current t ON t.item_id = e.ulid
        WHERE t.tag = :tag
          AND e.timestamp >= :dayStart
          AND e.timestamp < :dayEnd
          AND (:cursorTs IS NULL
               OR e.timestamp < :cursorTs
               OR (e.timestamp = :cursorTs AND e.id < :cursorId))
        ORDER BY e.timestamp DESC, e.id DESC
        LIMIT :limit
        """

    /** One item plus every tag it carries - the detail screen's read shape. */
    const val EVENT_BY_ID =
        """
        SELECT e.*,
               (SELECT GROUP_CONCAT(t2.tag)
                FROM item_tags_current t2
                WHERE t2.item_id = e.ulid) AS tags
        FROM events e
        WHERE e.id = :id
        """

    /**
     * Keyset walk for re-tagging. Ordered by `id` (insertion order) rather than
     * timestamp so a caller can persist a single cursor and always make forward
     * progress - including past items that produce no tags at all.
     */
    const val EVENTS_AFTER_ID =
        """
        SELECT id AS rowId, ulid, source, category, title, content
        FROM events
        WHERE id > :afterId
        ORDER BY id ASC
        LIMIT :limit
        """

    /**
     * Bounded candidate read for an authoring dry-run preview (ADR 0003 §12).
     *
     * Three rules keep it predictable:
     *  - it is always date-bounded on the indexed `events.timestamp` and capped
     *    by `:limit`, ordered newest first, so it can never walk the whole store;
     *  - each optional filter can only *narrow*; the evaluator still decides;
     *  - `text` and `field` predicates are absent on purpose. A regex cannot be
     *    indexed, and interpolating a user pattern into SQL is worse than slow -
     *    that is exactly §12's division of labour.
     *
     * `source` hits the indexed `events.source`; tag reads go through
     * `item_tags_current` (the active tagger's view, for the same reason tile
     * reads use it - `item_tags` is append-only and would double-count); a
     * mention reads the indexed `(kind, surface)` pair. `events.timestamp` is
     * indexed so the window and ordering use it.
     */
    const val EVENTS_RULE_PREVIEW_CANDIDATES =
        """
        SELECT e.ulid AS item_id, e.source AS source_id, e.title, e.content, e.timestamp
        FROM events e
        WHERE e.timestamp >= :since
          AND (:sourceId IS NULL OR e.source = :sourceId)
          AND (:tag IS NULL
               OR EXISTS (SELECT 1 FROM item_tags_current t
                          WHERE t.item_id = e.ulid AND t.tag = :tag))
          AND (:mentionKind IS NULL
               OR EXISTS (SELECT 1 FROM mentions m
                          WHERE m.item_id = e.ulid
                            AND m.kind = :mentionKind
                            AND m.surface = :mentionValue))
        ORDER BY e.timestamp DESC, e.id DESC
        LIMIT :limit
        """

    // --------------------------------------------------------------- taggers
    const val TAGGER_DEACTIVATE_ALL = "UPDATE taggers SET active = 0"

    const val TAGGER_ACTIVE = "SELECT * FROM taggers WHERE active = 1 LIMIT 1"

    /**
     * Enrichment candidates: items that carry a link but arrived with little or
     * no text of their own.
     *
     * `enriched_at IS NULL` means never attempted. A failed attempt is recorded
     * as `0`, so a dead link is not re-fetched on every pass - that is what stops
     * a retry storm. The length threshold is inline rather than a parameter
     * because it is a fixed property of "usefully empty", not a caller choice.
     */
    const val ENRICHMENT_CANDIDATES =
        """
        SELECT id, ulid, url, content, source, title
        FROM events
        WHERE url IS NOT NULL
          AND enriched_at IS NULL
          AND LENGTH(TRIM(content)) < 40
        ORDER BY timestamp DESC
        LIMIT :limit
        """

    const val ENRICHMENT_UPDATE_CONTENT =
        "UPDATE events SET content = :content, enriched_at = :enrichedAt WHERE id = :id"

    const val ENRICHMENT_MARK_ATTEMPTED =
        "UPDATE events SET enriched_at = :enrichedAt WHERE id = :id"

    // ------------------------------------------------------------- item_tags
    const val ITEM_TAGS_COUNT_FOR_TAGGER =
        "SELECT COUNT(*) FROM item_tags WHERE tagger_id = :taggerId"

    const val ITEM_TAGS_COUNT_BY_TAG =
        """
        SELECT tag, COUNT(*) AS count FROM item_tags
        GROUP BY tag
        ORDER BY count DESC
        """

    const val ITEM_TAGS_FOR_TAG =
        """
        SELECT item_id FROM item_tags
        WHERE tag = :tag AND tagger_id = :taggerId
        ORDER BY tagged_at DESC
        LIMIT :limit
        """

    const val ITEM_TAGS_DELETE_ALL = "DELETE FROM item_tags"

    /**
     * The stored tag set for a page of items, one query rather than one per row.
     *
     * Reads `item_tags_current` — the active tagger's view — so rule matching and
     * the tiles cannot disagree about an item's tags. Never called with an empty
     * list (`IN ()` matches nothing in some SQLite builds and errors in others).
     */
    const val ITEM_TAGS_CURRENT_FOR_ITEMS =
        "SELECT item_id, tag FROM item_tags_current WHERE item_id IN (:itemIds)"

    /**
     * The read surface for tags: rows from the active tagger only.
     *
     * Tagging is append-only, so `item_tags` holds several taggers' output at
     * once and reading the raw table would double-count every item. Tiles read
     * this view instead. `Retagger` is what backfills the active tagger, so the
     * window where an item carries only older tags is seconds long, at launch.
     *
     * Defined here rather than inline so the `@DatabaseView` and the migration
     * that creates it are guaranteed to be the same SQL.
     */
    const val ITEM_TAGS_CURRENT =
        """
        SELECT item_id, tag, tagger_id, confidence, entity, tagged_at
        FROM item_tags
        WHERE tagger_id = (SELECT id FROM taggers WHERE active = 1)
        """

    // ----------------------------------------------------------------- places
    const val PLACES_COUNT = "SELECT COUNT(*) FROM places"

    const val PLACES_ALL = "SELECT * FROM places"

    // --------------------------------------------------------------- mentions
    const val MENTIONS_FOR_ITEM = "SELECT * FROM mentions WHERE item_id = :itemId"

    /** One batched read for a page of rows: callers pass the page's ulids. */
    const val MENTIONS_FOR_ITEMS = "SELECT * FROM mentions WHERE item_id IN (:itemIds)"

    /**
     * Items with no mention rows yet, in insertion order. The `NOT IN` is what
     * makes a re-run converge: items that gained mentions drop out, and the
     * `id > :afterId` cursor is what keeps rows that yield *no* mentions from
     * pinning the walk in place (the same reason `Retagger` cursors on `id`).
     */
    const val MENTIONS_MISSING_ITEMS =
        """
        SELECT id AS rowId, ulid, title, content FROM events
        WHERE id > :afterId AND ulid NOT IN (SELECT item_id FROM mentions)
        ORDER BY id ASC
        LIMIT :limit
        """

    /**
     * One-time cleanup for surfaces the lexicon has since stopworded (towns
     * that lose to their English doubles: along, men, ...). The fix only ever
     * removes matches, so deleting the stale rows is complete — no rescan.
     */
    const val MENTIONS_DELETE_SURFACES = "DELETE FROM mentions WHERE lower(surface) IN (:surfaces)"

    // ----------------------------------------------------------------- parties
    const val PARTIES_COUNT = "SELECT COUNT(*) FROM parties"

    const val PARTIES_ALL = "SELECT * FROM parties"

    const val PARTY_SOURCES_ALL = "SELECT * FROM party_sources"

    const val PARTY_SOURCES_UPDATE_SYNC =
        "UPDATE party_sources SET last_sync_at = :syncedAt WHERE country = :country"

    // ----------------------------------------------------------------- sources
    // ADR 0003: the pre-v11 `rules` table was a source registry, now `sources`.
    // The write guards (`AND seeded = 0`) are what make bundled seeds add-only
    // even to a caller that bypasses the repository — the UI hiding buttons is
    // not enforcement.
    const val SOURCES_COUNT = "SELECT COUNT(*) FROM sources"

    const val SOURCES_ALL = "SELECT * FROM sources"

    const val SOURCES_ENABLED = "SELECT * FROM sources WHERE enabled = 1"

    const val SOURCES_UPDATE_ENABLED =
        "UPDATE sources SET enabled = :enabled, updated_at = :updatedAt WHERE id = :id AND seeded = 0"

    const val SOURCES_DELETE_USER_ONLY =
        "DELETE FROM sources WHERE id = :id AND seeded = 0"

    // ------------------------------------------------------------------- rules
    // ADR 0003: rules are pure evaluators over stored items. As with `sources`,
    // the write guards (`AND seeded = 0`) make bundled seeds add-only even to a
    // caller that bypasses the repository — the UI hiding buttons is not
    // enforcement. Condition and action JSON are validated by `core/rules`
    // (ConditionJson / ActionJson) at write, never by the schema.
    const val RULES_ALL = "SELECT * FROM rules"

    const val RULES_COUNT = "SELECT COUNT(*) FROM rules"

    /**
     * Edits a user rule in place. Every edit stamps `updated_at`; `position` is
     * written from the parsed action, so the column and `action_json` cannot
     * disagree. `color` is carried so an edit never silently drops a rule's own
     * colour — a null value here is an explicit "no colour", not an omission.
     * Returns rows touched.
     */
    const val RULES_UPDATE_USER_ONLY =
        """
        UPDATE rules
        SET name = :name,
            condition_json = :conditionJson,
            action_json = :actionJson,
            color = :color,
            position = :position,
            updated_at = :updatedAt
        WHERE id = :id AND seeded = 0
        """

    /** Guarded: `seeded` rows never match, so disabling a locked seed is an error. */
    const val RULES_UPDATE_ENABLED_USER_ONLY =
        "UPDATE rules SET enabled = :enabled, updated_at = :updatedAt WHERE id = :id AND seeded = 0"

    /** Guarded: only user rows delete. Returns rows removed. */
    const val RULES_DELETE_USER_ONLY = "DELETE FROM rules WHERE id = :id AND seeded = 0"

    // -------------------------------------------------------------- item_rules
    const val ITEM_RULES_ALL = "SELECT * FROM item_rules"

    // ------------------------------------------------------------- item_fields
    // ADR 0003 §13: typed extras materialised one row per (item, name), indexed
    // `(name, item_id)` for series windows and `(item_id)` for per-item reads.
    const val ITEM_FIELDS_FOR_ITEM = "SELECT * FROM item_fields WHERE item_id = :itemId"

    /** One batched read for a batch of items: callers pass the items' ulids. */
    const val ITEM_FIELDS_FOR_ITEMS = "SELECT * FROM item_fields WHERE item_id IN (:itemIds)"
}
