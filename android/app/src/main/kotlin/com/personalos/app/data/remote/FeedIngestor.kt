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
import com.personalos.app.core.rules.GnewsUrl
import com.personalos.app.core.rules.RssSpec
import com.personalos.app.core.rules.RuleKind
import com.personalos.app.core.rules.RuleSources
import com.personalos.app.core.rules.RuleSpecs
import com.personalos.app.core.rules.SearchSpec
import com.personalos.app.core.tag.SourceKind
import com.personalos.app.core.tag.TagInput
import com.personalos.app.core.tag.Tags
import com.personalos.app.core.tag.Ulid
import com.personalos.app.data.EventDao
import com.personalos.app.data.EventEntity
import com.personalos.app.data.MentionWriter
import com.personalos.app.data.RuleEntity
import com.personalos.app.data.TagWriter
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
    private val feeds: List<FeedSource> = FeedCatalog.SEEDS,
    private val refreshAfterMs: Long = DEFAULT_REFRESH_MS,
    /**
     * Enabled rules to poll alongside the catalog. Defaults to none, so the
     * catalog path works standalone; the app wires the rule repository here.
     * `search` rows and *user* `rss` rows are polled — seeded `rss` rows stay
     * inert because the catalog already drives those same URLs.
     */
    private val loadRules: suspend () -> List<RuleEntity> = { emptyList() },
    /**
     * The single fetch behind every poll, catalog or rule — same client, same
     * User-Agent ([Http.getText]). Injectable in tests.
     */
    private val fetch: (String) -> String = { url -> Http.getText(url) },
    /**
     * Raw fetch for rule polling, where the HTTP status is part of the health
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

    private val _ruleStatuses = MutableStateFlow(loadRuleStatuses())

    /**
     * Health of each *user* rule, keyed by rule id — the RSS list's per-row
     * status dot, last sync time and HTTP code. Separate from [statuses] so the
     * Sources "Feeds" section keeps showing catalog feeds only.
     */
    val ruleStatuses: StateFlow<List<FeedStatus>> = _ruleStatuses.asStateFlow()

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

            added += refreshSearchRules(now)
            added += refreshRssRules(now)

            _lastSyncAt.value = System.currentTimeMillis()
            Log.i(TAG, "refresh: +$added items from ${feeds.size} feeds")
            added
        }

    /**
     * Polls enabled `search` rules as Google News RSS, through the same
     * fetch/parse/store path as catalog feeds (same cache discipline, stale
     * fallback included). Deliberately outside [statuses]: rule health is not a
     * status-screen concern in v1, so no screen changes.
     *
     * Returns items added.
     */
    private suspend fun refreshSearchRules(now: Long): Int {
        val rules =
            runCatching { loadRules() }
                .onFailure { Log.w(TAG, "rules load failed", it) }
                .getOrElse { emptyList() }
                .filter { it.enabled && RuleKind.from(it.kind) == RuleKind.SEARCH }

        var added = 0
        for (rule in rules) {
            val spec =
                runCatching { RuleSpecs.parse(RuleKind.SEARCH, rule.specJson) as SearchSpec }
                    .onFailure { Log.w(TAG, "bad search spec for rule ${rule.id}", it) }
                    .getOrNull() ?: continue

            val key = "rule:${rule.id}"
            val entry = cache.read(key)
            val raw =
                if (entry != null && now - entry.at < refreshAfterMs) {
                    entry.value
                } else {
                    val body =
                        runCatching { fetch(GnewsUrl.build(spec.query)) }
                            .onFailure { Log.w(TAG, "rule fetch failed: ${rule.name}", it) }
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
                if (items.isNotEmpty()) added += ingestRule(rule, spec, items, now)
            }
        }
        return added
    }

    /**
     * Polls enabled user `rss` rules through the same fetch/parse/store path as
     * catalog feeds (same cache discipline, stale fallback included).
     * Deliberately outside [statuses]: rule health is not a status-screen
     * concern in v1, so no screen changes.
     *
     * Seeded `rss` rows are skipped: they mirror the catalog's URLs, which the
     * catalog pass already fetched — polling both would double every fetch and
     * dedupe to nothing.
     *
     * Returns items added.
     */
    private suspend fun refreshRssRules(now: Long): Int {
        val rules =
            runCatching { loadRules() }
                .onFailure { Log.w(TAG, "rules load failed", it) }
                .getOrElse { emptyList() }
                .filter { it.enabled && !it.seeded && RuleKind.from(it.kind) == RuleKind.RSS }

        val health = LinkedHashMap<String, FeedStatus>()
        _ruleStatuses.value.forEach { health[it.id] = it }

        var added = 0
        for (rule in rules) {
            val spec =
                runCatching { RuleSpecs.parse(RuleKind.RSS, rule.specJson) as RssSpec }
                    .onFailure { Log.w(TAG, "bad rss spec for rule ${rule.id}", it) }
                    .getOrNull() ?: continue

            val attemptAt = System.currentTimeMillis()
            // The rule's own interval when set, else the shared feed schedule.
            val intervalMs = rule.intervalSec?.times(1000L) ?: refreshAfterMs
            val key = "rule:${rule.id}"
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

            var addedForRule = 0
            if (body != null) {
                val items = runCatching { FeedParser.parse(body) }.getOrElse { emptyList() }
                if (items.isNotEmpty()) {
                    addedForRule = ingestUserRss(rule, spec, items, now)
                    added += addedForRule
                }
            }

            health[rule.id] =
                FeedStatus(
                    id = rule.id,
                    name = rule.name,
                    url = spec.url,
                    lastAttemptAt = attemptAt,
                    lastOkAt = if (ok) attemptAt else (health[rule.id]?.lastOkAt ?: 0L),
                    ok = ok,
                    lastError = error,
                    itemCount = addedForRule,
                    statusCode = code,
                )
        }

        // Only live rules, so a deleted feed's dot does not outlive it.
        val ordered = rules.mapNotNull { health[it.id] }
        _ruleStatuses.value = ordered
        persist(RULE_STATUS_KEY, ordered)

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
     * Inserts one search rule's items through the same store path as feeds:
     * dedupe by link on the existing `dedupe_key`, the rule's tags declared,
     * and the same items handed to the tag and mention writers.
     *
     * Google News RSS carries its outlet in the item's `<source>` element, but
     * [FeedParser] does not expose it and is deliberately not forked for v1 —
     * attribution stays the item's own title/summary, and the RULE name is what
     * the tagger sees as sender and the label path shows.
     */
    private suspend fun ingestRule(
        rule: RuleEntity,
        spec: SearchSpec,
        items: List<FeedItem>,
        now: Long,
    ): Int =
        storeItems(
            source = RuleSources.sourceFor(rule.id, spec),
            category = if (Tags.INCIDENT in spec.tags) FeedCategories.INCIDENT else FeedCategories.NEWS,
            sender = rule.name,
            language = spec.queryLangCode,
            declaredTags = spec.tags,
            items = items,
            now = now,
        )

    /**
     * Inserts one user RSS rule's items through the same store path as feeds:
     * dedupe by link on the existing `dedupe_key`, the rule's tags declared,
     * and the rule name as sender. The feed declares no language, so the
     * catalog default (`en`) applies — the tagger currently ignores language
     * anyway, and this keeps one ingest code path.
     */
    private suspend fun ingestUserRss(
        rule: RuleEntity,
        spec: RssSpec,
        items: List<FeedItem>,
        now: Long,
    ): Int =
        storeItems(
            source = RuleSources.sourceFor(rule.id, spec),
            category = if (Tags.INCIDENT in spec.tags) FeedCategories.INCIDENT else FeedCategories.NEWS,
            sender = rule.name,
            language = DEFAULT_LANGUAGE,
            declaredTags = spec.tags,
            items = items,
            now = now,
        )

    /** The one ingest code path: stores items, tags what landed, writes mentions. */
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
        var added = 0
        rowIds.forEachIndexed { index, rowId ->
            if (rowId == -1L) return@forEachIndexed
            added++
            val record = records[index]
            toTag +=
                record.ulid to
                TagInput(
                    text = "${record.title}\n${record.content}",
                    source = SourceKind.RSS,
                    sender = sender,
                    language = language,
                    // The rule declares its own tags; the tagger must not
                    // infer them from "this came over RSS".
                    declaredTags = declaredTags,
                )
        }
        if (toTag.isNotEmpty()) tagWriter.writeAll(toTag)
        if (toTag.isNotEmpty()) {
            mentionWriter.writeAll(toTag.map { (ulid, input) -> ulid to input.text })
        }
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

    private fun loadRuleStatuses(): List<FeedStatus> =
        cache
            .read(RULE_STATUS_KEY)
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
        private const val RULE_STATUS_KEY = "feed:rule-status"

        /** What a feed declares when it says nothing: mirrors `FeedSource`. */
        private const val DEFAULT_LANGUAGE = "en"
    }
}
