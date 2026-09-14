package com.personalos.app.data

import android.util.Log
import com.personalos.app.core.mention.BundledPartySource
import com.personalos.app.core.mention.MentionExtractor
import com.personalos.app.core.mention.PartyLexicon
import com.personalos.app.core.mention.PartySource
import com.personalos.app.core.mention.PlaceIndex

/**
 * Writes mention rows for ingested items. Mirrors [TagWriter]:
 *  - extraction **never blocks ingest**: any failure leaves the item stored
 *    and mention-less rather than dropping it;
 *  - rows are append-only (`INSERT ... IGNORE` on the natural key), so every
 *    entry point here is safe to re-run.
 *
 * The place index is loaded once from [loadPlaces] and cached — the data layer
 * loads, the injected [PlaceIndex] matches, and this class never queries
 * places itself beyond that single load.
 */
class MentionWriter(
    private val dao: MentionDao,
    private val loadPlaces: suspend () -> List<PlaceEntity>,
    partySource: PartySource = BundledPartySource,
) {
    private val partyLexicon = PartyLexicon(partySource)

    @Volatile
    private var placeIndex: PlaceIndex? = null

    /** Writes one item's mentions. Returns the rows written. */
    suspend fun write(
        itemId: String,
        text: String,
        now: Long = System.currentTimeMillis(),
    ): Int = writeAll(listOf(itemId to text), now)

    /** Writes a batch; one insert for the whole set. Returns rows written. */
    suspend fun writeAll(
        entries: List<Pair<String, String>>,
        now: Long = System.currentTimeMillis(),
    ): Int {
        if (entries.isEmpty()) return 0
        val extractor = extractorOrNull() ?: return 0
        val rows = ArrayList<MentionEntity>()
        for ((itemId, text) in entries) {
            val hits =
                runCatching { extractor.extract(text) }
                    .onFailure { Log.w(TAG, "extraction failed for $itemId", it) }
                    .getOrNull()
                    ?: continue
            hits.forEach { hit ->
                rows +=
                    MentionEntity(
                        itemId = itemId,
                        kind = hit.kind,
                        surface = hit.surface,
                        entityId = hit.entityId,
                        confidence = hit.confidence,
                        mentionedAt = now,
                    )
            }
        }
        if (rows.isNotEmpty()) {
            runCatching { dao.insertAll(rows) }
                .onFailure { Log.w(TAG, "mention insert failed for ${rows.size} rows", it) }
                .onSuccess { return rows.size }
            return 0
        }
        return 0
    }

    /**
     * Backfills mentions for items that have none yet. Walks in `id` order
     * with a cursor (like `Retagger`): items that yield no mentions still
     * advance the cursor, so the walk always terminates, and `IGNORE` makes a
     * re-run safe. Returns rows written.
     */
    suspend fun backfill(batchSize: Int = DEFAULT_BATCH_SIZE): Int {
        var afterId = 0L
        var written = 0
        while (true) {
            val batch = dao.missingItems(afterId, batchSize)
            if (batch.isEmpty()) break
            written += writeAll(batch.map { it.ulid to "${it.title}\n${it.content}" })
            afterId = batch.last().rowId
        }
        if (written > 0) Log.i(TAG, "backfilled $written mentions")
        return written
    }

    private suspend fun extractorOrNull(): MentionExtractor? {
        placeIndex?.let { return MentionExtractor(it, partyLexicon) }
        val places =
            runCatching { loadPlaces() }
                .onFailure { Log.w(TAG, "places load failed", it) }
                .getOrNull()
                ?: return null
        val index = PlaceIndex(places)
        placeIndex = index
        return MentionExtractor(index, partyLexicon)
    }

    private companion object {
        const val TAG = "Mentions"
        const val DEFAULT_BATCH_SIZE = 200
    }
}
