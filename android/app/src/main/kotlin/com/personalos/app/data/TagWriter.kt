package com.personalos.app.data

import android.util.Log
import com.personalos.app.core.tag.TagInput
import com.personalos.app.core.tag.Tagger
import com.personalos.app.core.tag.TaggerKind

/**
 * Writes tag rows for ingested items.
 *
 * Two rules from docs/ARCHITECTURE.md §10.4:
 *  - tagging **never blocks ingest**: any failure leaves the item stored and
 *    untagged rather than dropping it;
 *  - rows are append-only, so this only ever inserts.
 */
class TagWriter(
    private val dao: ItemTagDao,
    private val tagger: Tagger,
) {
    /**
     * Makes this tagger the active one, registering it if new — **promote only**.
     *
     * Each worker builds its own `AppContainer`, so a sync whose container never
     * ran the model upgrade carries the heuristic, and its ingest-time
     * `ensureActive` used to deactivate the cascade a backfill had just activated:
     * `item_tags_current` then reported the weaker tags. A stronger rung never
     * loses the flag to a weaker one. (Model removal, a genuine demotion, needs an
     * explicit deactivate, not this path.)
     */
    suspend fun ensureActive(now: Long = System.currentTimeMillis()) {
        val active = dao.activeTagger()
        if (active?.id == tagger.id) return
        val activeRank = active?.let { rankOf(it.kind) } ?: -1
        val myRank = rankOf(tagger.kind.name)
        val activeVersion = active?.version ?: -1
        // A stronger rung takes over; a *newer version of the same rung* does too
        // (cascade-v1 -> cascade-v2 is a real change and must win). A weaker rung
        // never overrides a stronger one, and no version regresses.
        val wins = myRank > activeRank || (myRank == activeRank && tagger.version > activeVersion)
        if (!wins) return
        dao.deactivateAll()
        dao.register(tagger.toEntity(now))
    }

    private fun rankOf(kindName: String): Int = runCatching { TaggerKind.valueOf(kindName).rank }.getOrDefault(-1)

    /** Tags one item. Returns the tags written (empty when nothing matched). */
    suspend fun write(
        itemId: String,
        input: TagInput,
    ): Set<String> {
        val result =
            runCatching { tagger.tag(input) }
                .onFailure { Log.w(TAG, "tagger ${tagger.id} failed for $itemId", it) }
                .getOrNull()
                ?: return emptySet()

        if (result.tags.isEmpty()) return emptySet()

        val now = System.currentTimeMillis()
        dao.insertAll(
            result.tags.map { tag ->
                ItemTagEntity(
                    itemId = itemId,
                    tag = tag,
                    taggerId = tagger.id,
                    confidence = result.confidence,
                    taggedAt = now,
                )
            },
        )
        return result.tags
    }

    /** Tags a batch in one tagger call; one insert for the whole set. Returns items tagged. */
    suspend fun writeAll(entries: List<Pair<String, TagInput>>): Int {
        if (entries.isEmpty()) return 0
        val now = System.currentTimeMillis()
        // One call for the whole batch, so a batched embedder can run it in a
        // single model pass. A failed batch leaves items untagged, never dropped.
        val results =
            runCatching { tagger.tagAll(entries.map { it.second }) }
                .onFailure { Log.w(TAG, "tagger ${tagger.id} failed for a batch of ${entries.size}", it) }
                .getOrNull()
                ?: return 0

        val rows = ArrayList<ItemTagEntity>()
        var tagged = 0
        entries.forEachIndexed { index, (itemId, _) ->
            val result = results.getOrNull(index) ?: return@forEachIndexed
            if (result.tags.isEmpty()) return@forEachIndexed
            tagged++
            result.tags.forEach { tag ->
                rows +=
                    ItemTagEntity(
                        itemId = itemId,
                        tag = tag,
                        taggerId = tagger.id,
                        confidence = result.confidence,
                        taggedAt = now,
                    )
            }
        }

        if (rows.isNotEmpty()) dao.insertAll(rows)
        return tagged
    }

    private companion object {
        const val TAG = "Tagger"
    }
}
