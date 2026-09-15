package com.personalos.app.data

import android.util.Log
import com.personalos.app.core.mention.BundledPartySource
import com.personalos.app.core.mention.ListPartySource
import com.personalos.app.core.mention.MentionExtractor
import com.personalos.app.core.mention.PartyEntry
import com.personalos.app.core.mention.PartyLexicon
import com.personalos.app.core.mention.PartySource
import com.personalos.app.core.mention.PlaceIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Writes mention rows for ingested items. Mirrors [TagWriter]:
 *  - extraction **never blocks ingest**: any failure leaves the item stored
 *    and mention-less rather than dropping it;
 *  - rows are append-only (`INSERT ... IGNORE` on the natural key), so every
 *    entry point here is safe to re-run.
 *
 * The place index is loaded once from [loadPlaces] and cached — the data layer
 * loads, the injected [PlaceIndex] matches, and this class never queries
 * places itself beyond that single load. Parties load the same way from the
 * `parties` table, falling back to [bundledParties] when the table is empty
 * (fresh install racing the seeder) so matching never goes dark.
 */
class MentionWriter(
    private val dao: MentionDao,
    private val loadPlaces: suspend () -> List<PlaceEntity>,
    private val loadParties: suspend () -> List<PartyEntry>,
    private val bundledParties: PartySource = BundledPartySource,
) {
    @Volatile
    private var placeIndex: PlaceIndex? = null

    @Volatile
    private var partyLexicon: PartyLexicon? = null

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
    suspend fun backfill(batchSize: Int = DEFAULT_BATCH_SIZE): Int =
        // Owns its dispatcher (see Retagger.run): this compiles the gazetteer
        // index and scans every unmentioned item.
        withContext(Dispatchers.IO) {
            var afterId = 0L
            var written = 0
            while (true) {
                val batch = dao.missingItems(afterId, batchSize)
                if (batch.isEmpty()) break
                written += writeAll(batch.map { it.ulid to "${it.title}\n${it.content}" })
                afterId = batch.last().rowId
            }
            if (written > 0) Log.i(TAG, "backfilled $written mentions")
            written
        }

    private suspend fun extractorOrNull(): MentionExtractor? {
        val index =
            placeIndex ?: run {
                val places =
                    runCatching { loadPlaces() }
                        .onFailure { Log.w(TAG, "places load failed", it) }
                        .getOrNull()
                        ?: return null
                PlaceIndex(places).also { placeIndex = it }
            }
        val lexicon =
            partyLexicon ?: run {
                val parties =
                    runCatching { loadParties() }
                        .onFailure { Log.w(TAG, "parties load failed, using bundled", it) }
                        .getOrNull()
                        .orEmpty()
                        .ifEmpty { bundledParties.parties() }
                PartyLexicon(ListPartySource("parties", parties)).also { partyLexicon = it }
            }
        return MentionExtractor(index, lexicon)
    }

    private companion object {
        const val TAG = "Mentions"
        const val DEFAULT_BATCH_SIZE = 200
    }
}
