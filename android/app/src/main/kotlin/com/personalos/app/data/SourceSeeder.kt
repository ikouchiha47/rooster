package com.personalos.app.data

import android.util.Log
import com.personalos.app.core.feed.FeedCatalog
import com.personalos.app.core.sources.GnewsUrl
import com.personalos.app.core.sources.SourceKind
import com.personalos.app.core.sources.SourceSpecs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reconciles the `sources` table with the bundled day-one set (ADR 0003) on
 * every launch. Mirrors [PlacesSeeder]/[PartySeeder] in spirit, but not in the
 * one detail that matters here:
 *
 * **Add-only, keyed by a stable id.** Each bundled seed carries a fixed `id`
 * (e.g. `seed:search:west-bengal`, `seed:rss:thehindu-top`), and a run inserts
 * only the seeds whose id is absent. A row that already exists is never updated
 * or deleted, so a user's edits and disabled state survive, and `seeded = true`
 * stays immutable. Repeated launches are therefore no-ops.
 *
 * **This is a reconcile, not a first-run gate.** Gating on `count() == 0` meant
 * a bundled seed added after a user's first launch was invisible forever, the
 * same defect [RuleSeeder] carried. Inserting by id lets a later release's new
 * source reach an existing install.
 *
 * A bundled seed whose spec changes in a later release is **not** applied: the
 * stored row is kept (that is the contract), and the divergence is logged. The
 * bundled set is 12 locked rows: 4 `search` sources (Bangalore, Kolkata,
 * Karnataka, West Bengal) plus 8 `rss` imports mirroring the bundled
 * [FeedCatalog] ids, urls and tags exactly.
 */
class SourceSeeder(
    private val dao: SourceDao,
) {
    suspend fun seed(now: Long = System.currentTimeMillis()): Int =
        // Owns its dispatcher (see Retagger.run).
        withContext(Dispatchers.IO) {
            runCatching {
                val stored = dao.all().associateBy { it.id }
                val missing = (searchSeeds(now) + rssSeeds(now)).filter { it.id !in stored }
                // Validate every missing spec before the first write: a bad
                // seed fails closed rather than writing a partial set.
                missing.forEach { SourceSpecs.parse(it.kind, it.specJson) }
                logDrift(stored)
                if (missing.isEmpty()) return@runCatching 0
                dao.insertAll(missing)
                Log.i(TAG, "sources seed: +${missing.size}, ${stored.size} kept")
                missing.size
            }.onFailure { Log.w(TAG, "sources seed failed", it) }
                .getOrDefault(0)
        }

    /**
     * Names every bundled seed whose stored spec differs from what this release
     * ships. The stored row is kept, so this warning is the only signal that a
     * release's edit did not land.
     */
    private fun logDrift(stored: Map<String, SourceEntity>) {
        (searchSeeds(0L) + rssSeeds(0L)).forEach { seed ->
            val row = stored[seed.id] ?: return@forEach
            if (row.seeded && (row.kind != seed.kind || row.specJson != seed.specJson)) {
                Log.w(TAG, "bundled source '${seed.id}' changed in a later release; keeping the stored row")
            }
        }
    }

    private companion object {
        const val TAG = "Sources"

        /**
         * A bundled row's id is fixed across releases — it is the seed's
         * identity for reconciliation, so it must never be derived from a
         * generated ULID or a mutable value like the query.
         */
        const val SEED_PREFIX = "seed"

        /** City/state coverage; the country-top edition is deliberately dropped (ADR 0003). */
        val SEARCH_QUERIES = listOf("Bangalore", "Kolkata", "Karnataka", "West Bengal")

        fun searchSeeds(now: Long): List<SourceEntity> =
            SEARCH_QUERIES.map { query ->
                SourceEntity(
                    // The slug is the same one `events.source` carries, so the
                    // row id and the ingested items share a stable spelling.
                    id = "$SEED_PREFIX:search:${GnewsUrl.slug(query)}",
                    name = query,
                    kind = SourceKind.SEARCH.serialName,
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

        fun rssSeeds(now: Long): List<SourceEntity> =
            FeedCatalog.SEEDS.map { source ->
                SourceEntity(
                    id = "$SEED_PREFIX:rss:${source.id}",
                    name = source.name,
                    kind = SourceKind.RSS.serialName,
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
