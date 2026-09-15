package com.personalos.app.data

import android.util.Log
import com.personalos.app.core.feed.FeedCatalog
import com.personalos.app.core.rules.RuleSpecs
import com.personalos.app.core.tag.Ulid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Seeds the `rules` table with the day-one set (ADR 0002) on first launch.
 * Mirrors [PlacesSeeder]/[PartySeeder]:
 *
 * Runs once: when the table is non-empty this is a single `COUNT(*)` and
 * nothing else. Inserts are `IGNORE`, so an interrupted seed resumes rather
 * than duplicating. Every spec is validated before insert, so a bad seed fails
 * closed (0 rows) rather than shipping a row the ingestor cannot run. Any
 * failure returns 0 — ingest falls back to the feed catalog, it never crashes
 * launch.
 *
 * 13 locked rows: 4 `search` rules (Bangalore, Kolkata, Karnataka, West Bengal)
 * plus 9 `rss` imports mirroring the bundled [FeedCatalog] ids, urls and tags
 * exactly.
 */
class RuleSeeder(
    private val dao: RuleDao,
) {
    suspend fun seed(now: Long = System.currentTimeMillis()): Int =
        // Owns its dispatcher (see Retagger.run).
        withContext(Dispatchers.IO) {
            runCatching {
                if (dao.count() > 0) return@runCatching 0
                val rows = searchSeeds(now) + rssSeeds(now)
                rows.forEach { RuleSpecs.parse(it.kind, it.specJson) }
                dao.insertAll(rows)
                Log.i(TAG, "seeded ${rows.size} rules")
                rows.size
            }.onFailure { Log.w(TAG, "rules seed failed", it) }
                .getOrDefault(0)
        }

    private companion object {
        const val TAG = "Rules"

        /** City/state coverage; the country-top edition is deliberately dropped (ADR 0002). */
        val SEARCH_QUERIES = listOf("Bangalore", "Kolkata", "Karnataka", "West Bengal")

        fun searchSeeds(now: Long): List<RuleEntity> =
            SEARCH_QUERIES.map { query ->
                RuleEntity(
                    id = Ulid.next(),
                    name = query,
                    kind = "search",
                    specJson =
                        JsonObject(
                            mapOf(
                                "query" to JsonPrimitive(query),
                                "query_lang_code" to JsonPrimitive("en"),
                                "source_locale" to JsonPrimitive("en-IN"),
                                "tags" to JsonArray(listOf(JsonPrimitive("news"))),
                            ),
                        ).toString(),
                    seeded = true,
                    enabled = true,
                    createdAt = now,
                    updatedAt = now,
                )
            }

        fun rssSeeds(now: Long): List<RuleEntity> =
            FeedCatalog.SEEDS.map { source ->
                RuleEntity(
                    id = Ulid.next(),
                    name = source.name,
                    kind = "rss",
                    specJson =
                        JsonObject(
                            mapOf(
                                "url" to JsonPrimitive(source.url),
                                "tags" to JsonArray(source.tags.sorted().map(::JsonPrimitive)),
                            ),
                        ).toString(),
                    seeded = true,
                    enabled = true,
                    createdAt = now,
                    updatedAt = now,
                )
            }
    }
}
