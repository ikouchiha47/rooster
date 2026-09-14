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
        SELECT COUNT(*) FROM events e
        JOIN item_tags_current t ON t.item_id = e.ulid
        WHERE t.tag = :tag
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
        SELECT id, ulid, url, content
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

    // ----------------------------------------------------------------- parties
    const val PARTIES_COUNT = "SELECT COUNT(*) FROM parties"

    const val PARTIES_ALL = "SELECT * FROM parties"

    const val PARTY_SOURCES_ALL = "SELECT * FROM party_sources"

    const val PARTY_SOURCES_UPDATE_SYNC =
        "UPDATE party_sources SET last_sync_at = :syncedAt WHERE country = :country"
}
