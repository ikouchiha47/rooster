package com.personalos.app.data.remote

import android.util.Log
import com.personalos.app.core.cache.StringCache
import com.personalos.app.core.feed.FeedCatalog
import com.personalos.app.core.feed.FeedParser
import com.personalos.app.core.feed.FeedSource
import com.personalos.app.core.feed.FeedStatus
import com.personalos.app.core.net.Http
import com.personalos.app.core.tag.SourceKind
import com.personalos.app.core.tag.TagInput
import com.personalos.app.core.tag.Ulid
import com.personalos.app.data.EventDao
import com.personalos.app.data.EventEntity
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
    private val feeds: List<FeedSource> = FeedCatalog.SEEDS,
    private val refreshAfterMs: Long = DEFAULT_REFRESH_MS,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _lastSyncAt = MutableStateFlow(0L)

    /** Wall-clock time of the last completed refresh, for the sync line. */
    val lastSyncAt: StateFlow<Long> = _lastSyncAt.asStateFlow()

    private val _statuses = MutableStateFlow(loadStatuses())

    /** Health of each configured feed, newest attempt last. */
    val statuses: StateFlow<List<FeedStatus>> = _statuses.asStateFlow()

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
                        val fetched = runCatching { Http.getText(feed.url) }
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

            _lastSyncAt.value = System.currentTimeMillis()
            Log.i(TAG, "refresh: +$added items from ${feeds.size} feeds")
            added
        }

    /** Inserts one feed's items and tags whatever actually landed. */
    private suspend fun ingest(
        feed: FeedSource,
        items: List<com.personalos.app.core.feed.FeedItem>,
        now: Long,
    ): Int {
        val records =
            items.map { item ->
                EventEntity(
                    ulid = Ulid.next(),
                    // URL is the natural key; title only as a fallback.
                    dedupeKey = item.link?.takeIf { it.isNotBlank() } ?: "rss:${feed.id}:${item.title}",
                    source = "rss:${feed.id}",
                    type = "feed",
                    category = feed.category,
                    timestamp = item.publishedAt ?: now,
                    title = item.title,
                    content = item.summary.orEmpty(),
                    entities = "[]",
                    location = null,
                    url = item.link,
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
                    sender = feed.name,
                    language = feed.language,
                    // The source declares its own tags; the tagger must not
                    // infer them from "this came over RSS".
                    declaredTags = feed.tags,
                )
        }
        if (toTag.isNotEmpty()) tagWriter.writeAll(toTag)
        return added
    }

    private fun loadStatuses(): List<FeedStatus> =
        cache
            .read(STATUS_KEY)
            ?.let { entry ->
                runCatching { json.decodeFromString<List<FeedStatus>>(entry.value) }.getOrNull()
            } ?: feeds.map { FeedStatus(id = it.id, name = it.name, url = it.url) }

    private fun persistStatuses(list: List<FeedStatus>) {
        runCatching { cache.write(STATUS_KEY, json.encodeToString(list), System.currentTimeMillis()) }
    }

    companion object {
        const val TAG = "Feed"
        const val CATEGORY_NEWS = "NEWS"
        const val DEFAULT_REFRESH_MS = 60L * 60 * 1000
        private const val STATUS_KEY = "feed:status"
    }
}
