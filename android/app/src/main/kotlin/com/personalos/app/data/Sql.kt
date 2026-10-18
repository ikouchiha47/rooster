package com.personalos.app.data

import com.personalos.app.core.tag.EmbedText

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

    /**
     * Messages is SMS: every branch is scoped to `source = 'sms'` (the SMS
     * adapter's identity), so feeds, observations and any future event kind
     * can never leak into the inbox. An allowlist, not a denylist.
     */
    const val EVENTS_COUNT_BY_MODE =
        """
        SELECT COUNT(*) FROM events
        WHERE source = 'sms'
          AND (:mode = 'all'
               OR (:mode = 'inbox' AND type = 'inbox')
               OR (:mode = 'sent'  AND type = 'sent')
               OR (:mode = 'other' AND type NOT IN ('inbox', 'sent')))
        """

    const val EVENTS_MAX_ID = "SELECT MAX(id) FROM events"

    /**
     * A change signal scoped to one source.
     *
     * [EVENTS_MAX_ID] is global: it ticks when *anything* lands, including a
     * weather reading or an FX rate. A screen that shows one source must not be
     * woken by another's writes, or it re-reads and rebuilds for changes it does
     * not display — which is what made Messages flicker during a feed sync.
     */
    const val EVENTS_MAX_ID_BY_SOURCE = "SELECT MAX(id) FROM events WHERE source = :source"

    /** Messages is SMS — see [EVENTS_COUNT_BY_MODE]. */
    const val EVENTS_PAGE_BY_MODE =
        """
        SELECT * FROM events
        WHERE source = 'sms'
          AND (:mode = 'all'
               OR (:mode = 'inbox' AND type = 'inbox')
               OR (:mode = 'sent'  AND type = 'sent')
               OR (:mode = 'other' AND type NOT IN ('inbox', 'sent')))
          AND (:cursorTs IS NULL
               OR timestamp < :cursorTs
               OR (timestamp = :cursorTs AND id < :cursorId))
        ORDER BY timestamp DESC, id DESC
        LIMIT :limit
        """

    /**
     * The wall (null category) never shows gauge observations — readings and
     * forecast days live in the Events tab, not the timeline. Category tabs
     * are unaffected: no observation carries their category.
     */
    const val EVENTS_COUNT_BY_CATEGORY =
        """
        SELECT COUNT(*) FROM events
        WHERE ((:category IS NULL AND type != 'observation') OR (category = :category))
        """

    const val EVENTS_COUNT_BY_SOURCE =
        """
        SELECT source, COUNT(*) AS count FROM events
        GROUP BY source
        ORDER BY count DESC
        """

    /** The wall excludes observations — see [EVENTS_COUNT_BY_CATEGORY]. */
    const val EVENTS_RADAR_PAGE =
        """
        SELECT * FROM events
        WHERE ((:category IS NULL AND type != 'observation') OR (category = :category))
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
     * Tag timelines show editorial items, never gauge observations: an
     * observation carries its topic tag so rules can match it, but
     * `weather:bengaluru:fc:2026-09-18` is not news. Display decision, so it
     * lives in the query the tiles call, not in the tagger.
     */
    const val EVENTS_BY_TAG_PAGE_ITEMS =
        """
        SELECT e.*,
               (SELECT GROUP_CONCAT(t2.tag)
                FROM item_tags_current t2
                WHERE t2.item_id = e.ulid) AS tags
        FROM events e
        JOIN item_tags_current t ON t.item_id = e.ulid
        WHERE t.tag = :tag
          AND e.type != 'observation'
          AND (:cursorTs IS NULL
               OR e.timestamp < :cursorTs
               OR (e.timestamp = :cursorTs AND e.id < :cursorId))
        ORDER BY e.timestamp DESC, e.id DESC
        LIMIT :limit
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
        SELECT id AS rowId, ulid, source, category, title, content, url, enriched_at AS enrichedAt
        FROM events
        WHERE id > :afterId
        ORDER BY id ASC
        LIMIT :limit
        """

    /**
     * One batch of the re-tag walk, **newest first**, scoped to a source family
     * (`:prefix`, e.g. `sms` or `rss:`), or every source when null.
     *
     * Per-family is what stops one loud source starving the rest: the walk has a
     * cursor *per family*, so `sms` (~2.6K rows) finishes on its own long before the
     * feed backlog does, instead of queuing behind it.
     */
    const val EVENTS_BEFORE_ID =
        """
        SELECT id AS rowId, ulid, source, category, title, content, url, enriched_at AS enrichedAt
        FROM events
        WHERE id < :beforeId
          AND (:prefix IS NULL OR source LIKE :prefix || '%')
        ORDER BY id DESC
        LIMIT :limit
        """

    /** The top of one source family, so its own walk starts from its newest row. */
    const val EVENTS_MAX_ID_FOR_PREFIX =
        """
        SELECT MAX(id) FROM events
        WHERE (:prefix IS NULL OR source LIKE :prefix || '%')
        """

    /**
     * The deferred-tag queue: items whose body was still pending when the walk
     * reached them, so they were not tagged on a possibly-stale headline.
     *
     * "Ready" is the negation of the pending shape ([EmbedText.stateOf]): a body at
     * or above the floor, or an attempted enrichment, or a row that cannot be
     * enriched at all (no URL). The floor is interpolated from the one owner so the
     * queue and the tagger can never disagree on what "has no body" means.
     */
    const val DEFERRED_TAGS_READY =
        """
        SELECT e.id AS rowId, e.ulid, e.source, e.category, e.title, e.content,
               e.url AS url, e.enriched_at AS enrichedAt
        FROM deferred_tags d
        JOIN events e ON e.ulid = d.item_id
        WHERE NOT (
            LENGTH(TRIM(e.content)) < ${EmbedText.USEFULLY_EMPTY_CHARS}
            AND e.enriched_at IS NULL
            AND e.url IS NOT NULL
        )
        ORDER BY e.id DESC
        LIMIT :limit
        """

    const val DEFERRED_TAGS_COUNT = "SELECT COUNT(*) FROM deferred_tags"

    const val DEFERRED_TAGS_REMOVE = "DELETE FROM deferred_tags WHERE item_id IN (:itemIds)"

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
     *
     * Oldest first on purpose: newest-first starved the backlog tail forever,
     * because hourly ingest adds short items faster than the 6-hourly batch of
     * 20 drains them. Fresh items already carry feed summaries ~90% of the time;
     * the gap this drain fills is concentrated in old backfill.
     */
    const val ENRICHMENT_CANDIDATES =
        """
        SELECT id, ulid, url, content, source, title
        FROM events
        WHERE url IS NOT NULL
          AND type != 'observation'
          AND enriched_at IS NULL
          AND LENGTH(TRIM(content)) < ${EmbedText.USEFULLY_EMPTY_CHARS}
        ORDER BY timestamp ASC, id ASC
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

    const val SOURCES_UPDATE_SPEC =
        "UPDATE sources SET spec_json = :specJson, updated_at = :updatedAt WHERE id = :id"

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

    // --------------------------------------------------------- removal
    // Removing the app's stored copy of an item. Dependents are deleted by
    // `item_id` (= events.ulid) before the event row, the same order the schema
    // migrations use, so a partial failure cannot orphan rows.
    //
    // This touches **only the app's copy**: the message on the device is
    // untouched, and offering to remove that too (WhatsApp's "also delete for
    // everyone") is a separate capability, deliberately not built.
    const val DELETE_ITEM_TAGS_BY_ITEM = "DELETE FROM item_tags WHERE item_id = :itemId"

    const val DELETE_MENTIONS_BY_ITEM = "DELETE FROM mentions WHERE item_id = :itemId"

    const val DELETE_ITEM_FIELDS_BY_ITEM = "DELETE FROM item_fields WHERE item_id = :itemId"

    const val DELETE_ITEM_RULES_BY_ITEM = "DELETE FROM item_rules WHERE item_id = :itemId"

    const val DELETE_EVENT_BY_ULID = "DELETE FROM events WHERE ulid = :ulid"

    // ---------------------------------------------------------------- saved
    // Bookmarks are a stamp on the item (1:0..1), so these are plain event
    // reads and one update — no join. `source` null means every saved item.
    const val EVENT_BY_ULID = "SELECT * FROM events WHERE ulid = :ulid LIMIT 1"

    const val EVENTS_SET_BOOKMARK =
        "UPDATE events SET bookmarked_at = :bookmarkedAt WHERE ulid = :ulid"

    /**
     * Saved items, newest save first, keyset-paged on the **save stamp** rather
     * than the publish time — the Saved view is ordered by when you saved, and
     * `bookmarked_at IS NOT NULL` rides the index declared on the entity.
     */
    const val EVENTS_SAVED =
        """
        SELECT * FROM events
        WHERE bookmarked_at IS NOT NULL
          AND (:source IS NULL OR source = :source)
          AND (:cursor IS NULL OR bookmarked_at < :cursor)
        ORDER BY bookmarked_at DESC
        LIMIT :limit
        """

    const val EVENTS_SAVED_COUNT =
        """
        SELECT COUNT(*) FROM events
        WHERE bookmarked_at IS NOT NULL AND (:source IS NULL OR source = :source)
        """

    // --------------------------------------------------------- conversations
    // Materialised per-sender rows for Messages (v20). See
    // MIGRATION_19_20_STATEMENTS for how the table is kept true. Every read is
    // scoped `source = 'sms'`: `events.title` is the sender only for SMS, so a
    // grouping that ignored the source would turn a headline into a sender.

    /**
     * The Recent section: bounded, no cursor — recency order can move under
     * writes, so it is never used to page. `pinned` first, then newest message.
     */
    const val CONVERSATIONS_RECENT =
        """
        SELECT c.*, e.content AS last_content
        FROM conversations c
        LEFT JOIN events e ON e.id = c.last_message_id
        WHERE c.source = 'sms'
          AND (:mode = 'all'
               OR (:mode = 'inbox' AND c.inbox_count > 0)
               OR (:mode = 'sent'  AND c.sent_count > 0)
               OR (:mode = 'other' AND c.other_count > 0))
        ORDER BY c.pinned DESC, c.last_message_id DESC
        LIMIT :limit
        """

    /**
     * The All section. Its cursor is the last `sender` of the previous page.
     * Ordering is alphabetical because that order does not move when a message
     * arrives — a recency order would shift rows under the cursor and skip or
     * duplicate them. `pinned` is always 0 in this slice, so the sender
     * comparison alone is an exact cursor; when pinning lands the cursor must
     * carry `pinned` too. Its index is the primary key's `(source, sender)`.
     */
    const val CONVERSATIONS_ALL =
        """
        SELECT c.*, e.content AS last_content
        FROM conversations c
        LEFT JOIN events e ON e.id = c.last_message_id
        WHERE c.source = 'sms'
          AND (:mode = 'all'
               OR (:mode = 'inbox' AND c.inbox_count > 0)
               OR (:mode = 'sent'  AND c.sent_count > 0)
               OR (:mode = 'other' AND c.other_count > 0))
          AND (:cursor IS NULL OR c.sender > :cursor)
        ORDER BY c.pinned DESC, c.sender ASC
        LIMIT :limit
        """

    /**
     * Every SMS sender, most recently active first — the picker's "Others" band:
     * people/numbers this app has messages with but that are not in the address
     * book. The address-book match happens in the UI, because it needs device
     * contacts and number normalisation the store knows nothing about.
     */
    const val CONVERSATIONS_SENDERS =
        """
        SELECT sender AS sender, last_at AS lastAt FROM conversations
        WHERE source = 'sms'
        ORDER BY last_at DESC
        """

    /**
     * Per-category conversation counts, for the tab strips. The category is the
     * one of each conversation's **latest message** — the same join and the same
     * rule the Timeline uses, so a tab's badge and its list cannot disagree.
     * Alerts has no entry here (there are no rules yet); the UI reads it as 0.
     */
    const val CONVERSATIONS_COUNTS_BY_CATEGORY =
        """
        SELECT e.category AS category, COUNT(*) AS count
        FROM conversations c
        JOIN events e ON e.id = c.last_message_id
        WHERE c.source = 'sms'
        GROUP BY e.category
        """

    /**
     * The Timeline: every conversation whose **latest message** is [categoryId],
     * newest last-message first, one row per sender. A conversation appears under
     * exactly one tab, because it has exactly one latest message.
     *
     * The category filter joins `events` on `last_message_id`; the cursor is the
     * `(lastAt, sender)` keyset of the previous page: `last_at` is the display
     * order and `sender` is the tiebreak that keeps the boundary exact when two
     * conversations share a millisecond — the primary key makes `sender` unique,
     * exactly like the thread's `(timestamp, id)`.
     */
    const val CONVERSATIONS_TIMELINE =
        """
        SELECT c.*, e.content AS last_content
        FROM conversations c
        JOIN events e ON e.id = c.last_message_id
        WHERE c.source = 'sms'
          AND e.category = :categoryId
          AND (:cursorAt IS NULL
               OR c.last_at < :cursorAt
               OR (c.last_at = :cursorAt AND c.sender > :cursorSender))
        ORDER BY c.last_at DESC, c.sender ASC
        LIMIT :limit
        """

    /**
     * One sender's thread: the existing `(timestamp, id)` keyset, filtered to
     * SMS and the sender. Same ordering as the flat timeline, so the thread and
     * the timeline cannot disagree about what is newest.
     */
    const val EVENTS_THREAD_PAGE =
        """
        SELECT * FROM events
        WHERE source = 'sms' AND title = :sender
          AND (:cursorTs IS NULL
               OR timestamp < :cursorTs
               OR (timestamp = :cursorTs AND id < :cursorId))
        ORDER BY timestamp DESC, id DESC
        LIMIT :limit
        """

    // ------------------------------------------------------------ messages search
    // The FTS5 trigram index over `events(title, content)` created by the v19
    // migration (see MIGRATION_18_19_STATEMENTS). Only Messages calls these, and
    // both are scoped to `source = 'sms'` exactly like every other Messages read,
    // so search can never surface a feed item - there is no global search.
    //
    // `:match` is an FTS expression built and quoted in `core/search`, bound as a
    // parameter, never raw input. `events_fts.rank` orders best textual match
    // first (FTS5's BM25); the JOIN drives off the FTS index rowid.
    const val EVENTS_SEARCH_MATCH =
        """
        SELECT e.* FROM events e
        JOIN events_fts ON events_fts.rowid = e.id
        WHERE events_fts MATCH :match AND e.source = 'sms'
        ORDER BY events_fts.rank
        LIMIT :limit
        """

    /**
     * The fallback for inputs the trigram index cannot serve (a term shorter
     * than three characters): a plain sub-string scan over the same two
     * columns, still SMS-only. `:pattern` is escaped and wrapped in `%` by
     * `core/search`; `ESCAPE '\'` is what makes a literal `%` or `_` in the
     * user's text a literal rather than a wildcard.
     */
    const val EVENTS_SEARCH_LIKE =
        """
        SELECT * FROM events
        WHERE source = 'sms'
          AND (title LIKE :pattern ESCAPE '\' OR content LIKE :pattern ESCAPE '\')
        ORDER BY timestamp DESC, id DESC
        LIMIT :limit
        """

    // -------------------------------------------------------------- item_rules
    const val ITEM_RULES_ALL = "SELECT * FROM item_rules"

    /**
     * A rule's fires, newest **item** first — the Watchers feed.
     *
     * Ordered by the item's own time, not `matched_at`. A fire written at ingest
     * has both roughly equal, but a *backfilled* fire is written now while its
     * item may be days old, so ordering by `matched_at` would sort a whole
     * backfill as simultaneous (and stable order would fall back to rowid).
     * Ordering by the item's time makes the feed read as a timeline and gives the
     * `LIMIT` a deterministic cut. `item_id` breaks ties, so the order is total.
     * `LIMIT` is always supplied.
     */
    const val ITEM_RULES_FOR_RULE =
        """
        SELECT ir.* FROM item_rules ir
        JOIN events e ON e.ulid = ir.item_id
        WHERE ir.rule_id = :ruleId
        ORDER BY e.timestamp DESC, ir.item_id DESC
        LIMIT :limit
        """

    /**
     * One keyset page of a rule's fires, oldest-but-newer-than the cursor — what
     * the Watchers "more" tap reads. The cursor is `(item timestamp, item id)`,
     * the same total order as [ITEM_RULES_FOR_RULE], so pages cannot skip or
     * duplicate as more items arrive. `:cursorTs` null starts the walk.
     */
    const val ITEM_RULES_FOR_RULE_PAGE =
        """
        SELECT ir.item_id AS item_id,
               ir.matched_at AS matched_at,
               e.title AS title,
               e.source AS source,
               e.content AS content,
               e.timestamp AS item_timestamp
        FROM item_rules ir
        JOIN events e ON e.ulid = ir.item_id
        WHERE ir.rule_id = :ruleId
          AND (:cursorTs IS NULL
               OR e.timestamp < :cursorTs
               OR (e.timestamp = :cursorTs AND ir.item_id < :cursorId))
        ORDER BY e.timestamp DESC, ir.item_id DESC
        LIMIT :limit
        """

    /** A rule's total fire count, so a section can say how many it is not showing. */
    const val ITEM_RULES_COUNT_FOR_RULE = "SELECT COUNT(*) FROM item_rules WHERE rule_id = :ruleId"

    /**
     * The cross-rule Alerts read: every stored match, newest **event** first, not
     * scoped to one rule (cf. [ITEM_RULES_FOR_RULE]). One row per matched event —
     * `GROUP_CONCAT` folds the rules that matched it into a single column, so a
     * page is a page of **events** and the Alerts badge counts events, not fires.
     *
     * Deliberately **not** scoped `source = 'sms'` like the rest of Messages: an
     * alert is about an item, whatever channel it arrived on (docs/plans/
     * categorization.md — Alerts is a view over matches, cross-source).
     *
     * Keyset-paged on `(events.timestamp, events.id)`, the same total order the
     * thread and timeline use, so pages cannot skip or duplicate as matches are
     * written. `:cursorTs` null starts the walk. `LIMIT` is always supplied.
     */
    const val ITEM_RULES_ALERTS_PAGE =
        """
        SELECT ir.item_id AS item_id,
               e.id AS event_id,
               MIN(ir.matched_at) AS matched_at,
               e.timestamp AS item_timestamp,
               GROUP_CONCAT(ir.rule_id) AS rule_ids
        FROM item_rules ir
        JOIN events e ON e.ulid = ir.item_id
        WHERE (:cursorTs IS NULL
               OR e.timestamp < :cursorTs
               OR (e.timestamp = :cursorTs AND e.id < :cursorId))
        GROUP BY ir.item_id
        ORDER BY e.timestamp DESC, e.id DESC
        LIMIT :limit
        """

    /**
     * The Alerts badge: matched **events**, the same grain [ITEM_RULES_ALERTS_PAGE]
     * lists, so the count and the list cannot disagree about how many there are.
     */
    const val ITEM_RULES_ALERTS_COUNT =
        """
        SELECT COUNT(DISTINCT ir.item_id)
        FROM item_rules ir
        JOIN events e ON e.ulid = ir.item_id
        """

    /** Every item's stored facts at once, so one fire batch costs one read per table. */
    const val EVENTS_BY_ULIDS = "SELECT * FROM events WHERE ulid IN (:ulids)"

    // ------------------------------------------------------------- item_fields
    // ADR 0003 §13: typed extras materialised one row per (item, name), indexed
    // `(name, item_id)` for series windows and `(item_id)` for per-item reads.
    const val ITEM_FIELDS_FOR_ITEM = "SELECT * FROM item_fields WHERE item_id = :itemId"

    /** One batched read for a batch of items: callers pass the items' ulids. */
    const val ITEM_FIELDS_FOR_ITEMS = "SELECT * FROM item_fields WHERE item_id IN (:itemIds)"

    /**
     * ADR 0005 T15: the series window read — last N samples for one
     * source + field, newest first, over the indexed `(events.source,
     * item_fields.name)` shape. Capped, never a full scan.
     */
    const val SERIES_WINDOW =
        """
        SELECT f.value_num, e.timestamp
        FROM item_fields f
        JOIN events e ON e.ulid = f.item_id
        WHERE e.source = :sourceId AND f.name = :field
        ORDER BY e.timestamp DESC
        LIMIT :limit
        """

    /** ADR 0005 T19: latest observation per identity for tiles. */
    const val LATEST_OBSERVATION_BY_SOURCE =
        """
        SELECT e.* FROM events e
        WHERE e.source = :sourceId AND e.type = 'observation'
        ORDER BY e.timestamp DESC
        LIMIT 1
        """

    /** ADR 0005 T19: latest observations for a set of identities. */
    const val LATEST_OBSERVATIONS_BY_SOURCES =
        """
        SELECT e.* FROM events e
        WHERE e.source IN (:sourceIds) AND e.type = 'observation'
        ORDER BY e.timestamp DESC
        """

    /**
     * Forecast week: every `weather:<slug>:fc:<date>` row, oldest date first.
     * Exact-identity reads (`latestObservation`) can never collide with these —
     * the `:fc:` segment keeps the namespaces apart.
     */
    const val OBSERVATIONS_BY_PREFIX =
        """
        SELECT e.* FROM events e
        WHERE e.source LIKE :prefix || '%' AND e.type = 'observation'
        ORDER BY e.source ASC
        """

    /** ADR 0005 T8/T17: gauge upsert-by-bucket needs the row for a dedupe key. */
    const val EVENT_BY_DEDUPE_KEY = "SELECT * FROM events WHERE dedupe_key = :dedupeKey LIMIT 1"

    const val EVENT_UPDATE_OBSERVATION =
        "UPDATE events SET timestamp = :timestamp, title = :title, content = :content WHERE ulid = :ulid"

    // --------------------------------------------------------------- sync_runs
    // The activity log behind the Radar Events tab: one row per sync run per
    // source, so a quiet poll and a crashed poll never look the same.
    const val SYNC_RUNS_LATEST_PER_SOURCE =
        """
        SELECT * FROM sync_runs
        WHERE id IN (SELECT MAX(id) FROM sync_runs GROUP BY source_id)
        ORDER BY finished_at DESC
        """

    const val SYNC_RUNS_FOR_SOURCE =
        """
        SELECT * FROM sync_runs
        WHERE source_id = :sourceId
        ORDER BY finished_at DESC
        LIMIT :limit
        """

    const val SYNC_RUNS_SOURCE_COUNT = "SELECT COUNT(DISTINCT source_id) FROM sync_runs"

    const val SYNC_RUNS_PRUNE = "DELETE FROM sync_runs WHERE finished_at < :cutoffMs"

    // ------------------------------------------------------------ calendar
    // Dated observances from calendar feeds. `starts_at` is indexed, so
    // "next N days for these regions" is a range scan, never a table walk.
    const val CALENDAR_UPCOMING =
        """
        SELECT * FROM calendar_dates
        WHERE region IN (:regions) AND starts_at >= :fromMs AND starts_at <= :toMs
        ORDER BY starts_at ASC, name ASC
        """

    const val CALENDAR_COUNT = "SELECT COUNT(*) FROM calendar_dates"

    const val CALENDAR_DELETE_STALE =
        "DELETE FROM calendar_dates WHERE source = :source AND fetched_at < :fetchedBefore"

    // ----------------------------------------------------------------- catalog
    // ADR 0005 T6: producer descriptors as data. Add-only like sources/rules.
    const val KINDS_ALL = "SELECT * FROM kinds"

    const val FACETS_ALL = "SELECT * FROM facets"

    const val KIND_FACETS_ALL = "SELECT * FROM kind_facets"
}
