package com.personalos.app.data

import android.util.Log
import com.personalos.app.core.tag.TagInput
import com.personalos.app.core.tag.Tagger

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
    /** Makes this tagger the active one, registering it if it is new. */
    suspend fun ensureActive(now: Long = System.currentTimeMillis()) {
        if (dao.activeTagger()?.id == tagger.id) return
        dao.deactivateAll()
        dao.register(tagger.toEntity(now))
    }

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

    /** Tags a batch; one insert for the whole set. Returns items tagged. */
    suspend fun writeAll(entries: List<Pair<String, TagInput>>): Int {
        if (entries.isEmpty()) return 0
        val now = System.currentTimeMillis()
        val rows = ArrayList<ItemTagEntity>()
        var tagged = 0

        for ((itemId, input) in entries) {
            val result =
                runCatching { tagger.tag(input) }
                    .onFailure { Log.w(TAG, "tagger ${tagger.id} failed for $itemId", it) }
                    .getOrNull()
                    ?: continue
            if (result.tags.isEmpty()) continue
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
