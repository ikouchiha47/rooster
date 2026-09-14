package com.personalos.app.core.tag

import android.util.Log
import com.personalos.app.core.cache.StringCache
import com.personalos.app.core.feed.FeedCatalog
import com.personalos.app.data.EventDao
import com.personalos.app.data.RetagCandidate
import com.personalos.app.data.TagWriter

/**
 * Backfills tags for items that predate the active tagger, or that arrived
 * before it existed.
 *
 * Items are tagged once at insert, so this exists for the other case: a new
 * tagger version taking over an existing store. It walks items in `id` order
 * and persists a **cursor per tagger**, which gives two properties that matter:
 *
 *  - **It converges.** A cursor on `id` advances past every row, including rows
 *    that produce no tags. Selecting "items with no tags" instead would re-read
 *    the same untaggable rows forever and never reach the rest of the store.
 *  - **A new tagger re-tags everything.** The cursor is keyed by tagger id, so
 *    bumping the version starts a fresh walk and the old tagger's rows stay put.
 *
 * Writes are `INSERT ... IGNORE` on `(item_id, tag, tagger_id)`, so re-running
 * is safe and never duplicates a tag (docs/ARCHITECTURE.md §11.5).
 */
class Retagger(
    private val dao: EventDao,
    private val tagWriter: TagWriter,
    private val cache: StringCache,
    private val tagger: Tagger,
) {
    /**
     * Tags up to `batchSize * maxBatches` items. Returns how many items gained
     * at least one tag. Safe to call on every launch: once the cursor reaches
     * the end, this costs a single empty query.
     */
    suspend fun run(
        batchSize: Int = DEFAULT_BATCH_SIZE,
        maxBatches: Int = DEFAULT_MAX_BATCHES,
    ): Int {
        var cursor = cache.read(cursorKey())?.value?.toLongOrNull() ?: 0L
        var tagged = 0
        var batches = 0

        while (batches < maxBatches) {
            val batch = dao.itemsAfter(cursor, batchSize)
            if (batch.isEmpty()) break

            tagged += tagWriter.writeAll(batch.map { it.toTagInput() })
            cursor = batch.last().rowId
            batches++

            // Persist after each batch so a kill mid-walk resumes, not restarts.
            cache.write(cursorKey(), cursor.toString(), System.currentTimeMillis())
        }

        if (tagged > 0) {
            Log.i(TAG, "re-tag with ${tagger.id}: $tagged items tagged (cursor=$cursor)")
        }
        return tagged
    }

    private fun cursorKey() = "retag:cursor:${tagger.id}"

    private fun RetagCandidate.toTagInput(): Pair<String, TagInput> {
        val isSms = source == SMS_SOURCE

        return ulid to
            TagInput(
                text = "$title\n$content",
                source = if (isSms) SourceKind.SMS else SourceKind.RSS,
                // For SMS the title column holds the sender, which drives the
                // personal/promotional priors.
                sender = title.takeIf { isSms },
                // Recover the source's full tag set from the catalog rather than
                // the single `category` column, so a re-tag restores *all* of a
                // source's tags. SMS rows store an SmsClass name in `category`,
                // which is not a tag, so they declare nothing.
                declaredTags = if (isSms) emptySet() else FeedCatalog.bySource(source)?.tags.orEmpty(),
            )
    }

    private companion object {
        const val TAG = "Retagger"
        const val SMS_SOURCE = "sms"
        const val DEFAULT_BATCH_SIZE = 200
        const val DEFAULT_MAX_BATCHES = 40
    }
}
