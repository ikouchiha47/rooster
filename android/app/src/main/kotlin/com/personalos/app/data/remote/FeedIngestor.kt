package com.personalos.app.data.remote

import android.util.Log
import com.personalos.app.core.cache.StringCache
import com.personalos.app.core.feed.FeedCatalog
import com.personalos.app.core.feed.FeedCategories
import com.personalos.app.core.feed.FeedItem
import com.personalos.app.core.feed.FeedParser
import com.personalos.app.core.feed.FeedSource
import com.personalos.app.core.feed.FeedStatus
import com.personalos.app.core.net.Http
import com.personalos.app.core.sources.GnewsUrl
import com.personalos.app.core.sources.RssSpec
import com.personalos.app.core.sources.SearchSpec
import com.personalos.app.core.sources.SourceKeys
import com.personalos.app.core.sources.SourceKind
import com.personalos.app.core.sources.SourceSpecs
import com.personalos.app.core.tag.TagInput
import com.personalos.app.core.tag.Tags
import com.personalos.app.core.tag.Transport
import com.personalos.app.core.tag.Ulid
import com.personalos.app.data.EventDao
import com.personalos.app.data.EventEntity
import com.personalos.app.data.MentionWriter
import com.personalos.app.data.RuleWriter
import com.personalos.app.data.SourceEntity
import com.personalos.app.data.TagWriter
import com.personalos.app.data.toRuleItemSeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Pulls the subscribed feeds and writes their items into the same events table
 * the rest of the app reads, so news shows up in Radar with no special-casing.
 *
 * Each feed's raw payload is cached, so re-opening the screen does not refetch;
 * a feed is only fetched again once its cache entry is older than [refreshAfterMs].
 * A stale cache is still better than nothing, so a failed fetch falls back to it.
 *
 * Every inserted item is tagged once (docs/ARCHITECTURE.md §10) and its tags
 * stored append-only against the item's ULID (§11).
 *
 * Per-feed health is kept in [statuses] and persisted, so the Sources screen
 * has something to show after a restart.
 */
class FeedIngestor(
    private val dao: EventDao,
    private val cache: StringCache,
    private val tagWriter: TagWriter,
    private val mentionWriter: MentionWriter,
    /**
     * Evaluates enabled rules against items that actually landed (ADR 0003 §10,
     * R2). Takes the landed rows only, so a re-fetched item does not re-trigger
     * evaluation — and its tagged/mentioned facts are already stored by then.
     */
    private val ruleWriter: RuleWriter,
    private val feeds: List<FeedSource> = FeedCatalog.SEEDS,
    private val refreshAfterMs: Long = DEFAULT_REFRESH_MS,
    /**
     * Enabled sources to poll alongside the catalog. Defaults to none, so the
     * catalog path works standalone; the app wires the source repository here.
     * `search` rows and *user* `rss` rows are polled — seeded `rss` rows stay
     * inert because the catalog already drives those same URLs.
     */
    private val loadSources: suspend () -> List<SourceEntity> = { emptyList() },
    /**
     * The single fetch behind every poll, catalog or source — same client, same
     * User-Agent ([Http.getText]). Injectable in tests.
     */
    private val fetch: (String) -> String = { url -> Http.getText(url) },
    /**
     * Raw fetch for source polling, where the HTTP status is part of the health
     * the RSS list shows. Same client, same User-Agent. Injectable in tests.
     */
    private val fetchRaw: (String) -> Http.RawResponse = { url -> Http.getRaw(url) },
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _lastSyncAt = MutableStateFlow(0L)

    /** Wall-clock time of the last completed refresh, for the sync line. */
    val lastSyncAt: StateFlow<Long> = _lastSyncAt.asStateFlow()

    private val _statuses = MutableStateFlow(loadStatuses())

    /** Health of each configured feed, newest attempt last. */
    val statuses: StateFlow<List<FeedStatus>> = _statuses.asStateFlow()

    private val _sourceStatuses = MutableStateFlow(loadSourceStatuses())

    /**
     * Health of each *user* source, keyed by source id — the RSS list's per-row
     * status dot, last sync time and HTTP code. Separate from [statuses] so the
     * Sources "Feeds" section keeps showing catalog feeds only.
     */
    val sourceStatuses: StateFlow<List<FeedStatus>> = _sourceStatuses.asStateFlow()

    suspend fun refresh(): Int =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            var added = 0

            tagWriter.ensureActive(now)

            // Keep prior state, and give never-synced feeds a row up front.
            val byId = LinkedHashMap<String, FeedStatus>()
            _statuses.value.forEach { byId[it.id] = it }
            feeds.forEach { feed ->
                if (!byId.containsKey(feed.id)) {
                    byId[feed.id] = FeedStatus(id = feed.id, name = feed.name, url = feed.url)
                }
            }

            for (feed in feeds) {
                val attemptAt = System.currentTimeMillis()
                val key = "feed:${feed.id}"
                val entry = cache.read(key)
                val fresh = entry != null && now - entry.at < refreshAfterMs

                var ok = false
                var error: String? = null

                val raw =
                    if (fresh) {
                        ok = true
                        entry?.value
                    } else {
                        val fetched = runCatching { fetch(feed.url) }
                        val body = fetched.getOrNull()
                        if (body != null) {
                            cache.write(key, body, now)
                            ok = true
                            body
                        } else {
                            error = fetched.exceptionOrNull()?.message ?: "fetch failed"
                            // Serve the stale copy rather than showing nothing.
                            entry?.value?.also { ok = true }
                        }
                    }

                var addedForFeed = 0
                if (raw != null) {
                    val items = runCatching { FeedParser.parse(raw) }.getOrElse { emptyList() }
                    if (items.isNotEmpty()) {
                        addedForFeed = ingest(feed, items, now)
                        added += addedForFeed
                    }
                }

                byId[feed.id] =
                    FeedStatus(
                        id = feed.id,
                        name = feed.name,
                        url = feed.url,
                        lastAttemptAt = attemptAt,
                        lastOkAt = if (ok) attemptAt else (byId[feed.id]?.lastOkAt ?: 0L),
                        ok = ok,
                        lastError = error,
                        itemCount = addedForFeed,
                    )
            }

            val ordered = feeds.mapNotNull { byId[it.id] }
            _statuses.value = ordered
            persistStatuses(ordered)

            added += refreshSearchSources(now)
            added += refreshRssSources(now)

            _lastSyncAt.value = System.currentTimeMillis()
            Log.i(TAG, "refresh: +$added items from ${feeds.size} feeds")
            added
        }

    /**
     * Polls enabled `search` sources as Google News RSS, through the same
     * fetch/parse/store path as catalog feeds (same cache discipline, stale
     * fallback included). Deliberately outside [statuses]: source health is not
     * a status-screen concern in v1, so no screen changes.
     *
     * Returns items added.
     */
    private suspend fun refreshSearchSources(now: Long): Int {
        val sources =
            runCatching { loadSources() }
                .onFailure { Log.w(TAG, "sources load failed", it) }
                .getOrElse { emptyList() }
                .filter { it.enabled && SourceKind.from(it.kind) == SourceKind.SEARCH }

        var added = 0
        for (source in sources) {
            val spec =
                runCatching { SourceSpecs.parse(SourceKind.SEARCH, source.specJson) as SearchSpec }
                    .onFailure { Log.w(TAG, "bad search spec for source ${source.id}", it) }
                    .getOrNull() ?: continue

            // Frozen across the v11 rename: changing this key drops cached bodies.
            val key = "rule:${source.id}"
            val entry = cache.read(key)
            val raw =
                if (entry != null && now - entry.at < refreshAfterMs) {
                    entry.value
                } else {
                    val body =
                        runCatching { fetch(GnewsUrl.build(spec.query)) }
                            .onFailure { Log.w(TAG, "source fetch failed: ${source.name}", it) }
                            .getOrNull()
                    if (body != null) {
                        cache.write(key, body, now)
                        body
                    } else {
                        // Serve the stale copy rather than showing nothing.
                        entry?.value
                    }
                }

            if (raw != null) {
                val items = runCatching { FeedParser.parse(raw) }.getOrElse { emptyList() }
                if (items.isNotEmpty()) added += ingestSource(source, spec, items, now)
            }
        }
        return added
    }

    /**
     * Polls enabled user `rss` sources through the same fetch/parse/store path as
     * catalog feeds (same cache discipline, stale fallback included).
     * Deliberately outside [statuses]: source health is not a status-screen
     * concern in v1, so no screen changes.
     *
     * Seeded `rss` rows are skipped: they mirror the catalog's URLs, which the
     * catalog pass already fetched — polling both would double every fetch and
     * dedupe to nothing.
     *
     * Returns items added.
     */
    private suspend fun refreshRssSources(now: Long): Int {
        val sources =
            runCatching { loadSources() }
                .onFailure { Log.w(TAG, "sources load failed", it) }
                .getOrElse { emptyList() }
                .filter { it.enabled && !it.seeded && SourceKind.from(it.kind) == SourceKind.RSS }

        val health = LinkedHashMap<String, FeedStatus>()
        _sourceStatuses.value.forEach { health[it.id] = it }

        var added = 0
        for (source in sources) {
            val spec =
                runCatching { SourceSpecs.parse(SourceKind.RSS, source.specJson) as RssSpec }
                    .onFailure { Log.w(TAG, "bad rss spec for source ${source.id}", it) }
                    .getOrNull() ?: continue

            val attemptAt = System.currentTimeMillis()
            // The source's own interval when set, else the shared feed schedule.
            val intervalMs = source.intervalSec?.times(1000L) ?: refreshAfterMs
            // Frozen across the v11 rename: changing this key drops cached bodies.
            val key = "rule:${source.id}"
            val entry = cache.read(key)

            var ok = false
            var code: Int? = null
            var error: String? = null
            val body =
                if (entry != null && now - entry.at < intervalMs) {
                    ok = true
                    entry.value
                } else {
                    val fetched = runCatching { fetchRaw(spec.url) }
                    val response = fetched.getOrNull()
                    if (response != null) {
                        code = response.code
                        if (response.code in 200..299 && response.body.isNotBlank()) {
                            cache.write(key, response.body, now)
                            ok = true
                            response.body
                        } else {
                            // Reachable but refused (403 gate, 5xx, empty body):
                            // report the code, keep the stale copy on screen.
                            error = "HTTP ${response.code}"
                            entry?.value?.also { ok = true }
                        }
                    } else {
                        error = fetched.exceptionOrNull()?.message ?: "fetch failed"
                        entry?.value?.also { ok = true }
                    }
                }

            var addedForSource = 0
            if (body != null) {
                val items = runCatching { FeedParser.parse(body) }.getOrElse { emptyList() }
                if (items.isNotEmpty()) {
                    addedForSource = ingestUserFeed(source, spec, items, now)
                    added += addedForSource
                }
            }

            health[source.id] =
                FeedStatus(
                    id = source.id,
                    name = source.name,
                    url = spec.url,
                    lastAttemptAt = attemptAt,
                    lastOkAt = if (ok) attemptAt else (health[source.id]?.lastOkAt ?: 0L),
                    ok = ok,
                    lastError = error,
                    itemCount = addedForSource,
                    statusCode = code,
                )
        }

        // Only live sources, so a deleted feed's dot does not outlive it.
        val ordered = sources.mapNotNull { health[it.id] }
        _sourceStatuses.value = ordered
        persist(SOURCE_STATUS_KEY, ordered)

        return added
    }

    /** Inserts one feed's items and tags whatever actually landed. */
    private suspend fun ingest(
        feed: FeedSource,
        items: List<FeedItem>,
        now: Long,
    ): Int =
        storeItems(
            source = FeedCatalog.SOURCE_PREFIX + feed.id,
            category = feed.category,
            sender = feed.name,
            language = feed.language,
            declaredTags = feed.tags,
            items = items,
            now = now,
        )

    /**
     * Inserts one search source's items through the same store path as feeds:
     * dedupe by link on the existing `dedupe_key`, the source's tags declared,
     * and the same items handed to the tag and mention writers.
     *
     * Google News RSS carries its outlet in the item's `<source>` element, but
     * [FeedParser] does not expose it and is deliberately not forked for v1 —
     * attribution stays the item's own title/summary, and the SOURCE name is what
     * the tagger sees as sender and the label path shows.
     */
    private suspend fun ingestSource(
        source: SourceEntity,
        spec: SearchSpec,
        items: List<FeedItem>,
        now: Long,
    ): Int =
        storeItems(
            source = SourceKeys.sourceFor(source.id, spec),
            category = if (Tags.INCIDENT in spec.tags) FeedCategories.INCIDENT else FeedCategories.NEWS,
            sender = source.name,
            language = spec.queryLangCode,
            declaredTags = spec.tags,
            items = items,
            now = now,
        )

    /**
     * Inserts one user RSS source's items through the same store path as feeds:
     * dedupe by link on the existing `dedupe_key`, the source's tags declared,
     * and the source name as sender. The feed declares no language, so the
     * catalog default (`en`) applies — the tagger currently ignores language
     * anyway, and this keeps one ingest code path.
     */
    private suspend fun ingestUserFeed(
        source: SourceEntity,
        spec: RssSpec,
        items: List<FeedItem>,
        now: Long,
    ): Int =
        storeItems(
            source = SourceKeys.sourceFor(source.id, spec),
            category = if (Tags.INCIDENT in spec.tags) FeedCategories.INCIDENT else FeedCategories.NEWS,
            sender = source.name,
            language = DEFAULT_LANGUAGE,
            declaredTags = spec.tags,
            items = items,
            now = now,
        )

    /** The one ingest code path: stores items, tags what landed, writes mentions, evaluates rules. */
    private suspend fun storeItems(
        source: String,
        category: String,
        sender: String,
        language: String,
        declaredTags: Set<String>,
        items: List<FeedItem>,
        now: Long,
    ): Int {
        val records =
            items.map { item ->
                EventEntity(
                    ulid = Ulid.next(),
                    // URL is the natural key; title only as a fallback.
                    dedupeKey = item.link?.takeIf { it.isNotBlank() } ?: "$source:${item.title}",
                    source = source,
                    type = "feed",
                    category = category,
                    timestamp = item.publishedAt ?: now,
                    title = item.title,
                    content = item.summary.orEmpty(),
                    entities = "[]",
                    location = null,
                    url = item.link,
                    ingestedAt = now,
                )
            }

        // The unique index on dedupe_key is what keeps repeats out.
        val rowIds = dao.insertAll(records)

        val toTag = ArrayList<Pair<String, TagInput>>(records.size)
        val landed = ArrayList<EventEntity>(records.size)
        var added = 0
        rowIds.forEachIndexed { index, rowId ->
            if (rowId == -1L) return@forEachIndexed
            added++
            val record = records[index]
            landed += record
            toTag +=
                record.ulid to
                TagInput(
                    text = "${record.title}\n${record.content}",
                    source = Transport.RSS,
                    sender = sender,
                    language = language,
                    // The source declares its own tags; the tagger must not
                    // infer them from "this came over RSS".
                    declaredTags = declaredTags,
                )
        }
        if (toTag.isNotEmpty()) tagWriter.writeAll(toTag)
        if (toTag.isNotEmpty()) {
            mentionWriter.writeAll(toTag.map { (ulid, input) -> ulid to input.text })
        }
        // Only rows that actually landed, and only after their tags and mentions
        // are stored, so evaluation reads the same facts the rest of the app does.
        if (landed.isNotEmpty()) ruleWriter.writeAll(landed.map { it.toRuleItemSeed() })
        return added
    }

    private fun loadStatuses(): List<FeedStatus> =
        cache
            .read(STATUS_KEY)
            ?.let { entry ->
                runCatching { json.decodeFromString<List<FeedStatus>>(entry.value) }.getOrNull()
            } ?: feeds.map { FeedStatus(id = it.id, name = it.name, url = it.url) }

    private fun persistStatuses(list: List<FeedStatus>) {
        persist(STATUS_KEY, list)
    }

    private fun loadSourceStatuses(): List<FeedStatus> =
        cache
            .read(SOURCE_STATUS_KEY)
            ?.let { entry ->
                runCatching { json.decodeFromString<List<FeedStatus>>(entry.value) }.getOrNull()
            }.orEmpty()

    private fun persist(
        key: String,
        list: List<FeedStatus>,
    ) {
        runCatching { cache.write(key, json.encodeToString(list), System.currentTimeMillis()) }
    }

    companion object {
        const val TAG = "Feed"
        const val CATEGORY_NEWS = "NEWS"
        const val DEFAULT_REFRESH_MS = 60L * 60 * 1000

        private const val STATUS_KEY = "feed:status"

        /** Value frozen across the v11 rename; changing it resets every status dot. */
        private const val SOURCE_STATUS_KEY = "feed:rule-status"

        /** What a feed declares when it says nothing: mirrors `FeedSource`. */
        private const val DEFAULT_LANGUAGE = "en"
    }
}
