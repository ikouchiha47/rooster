package com.personalos.app.core.tag

import android.util.Log
import com.personalos.app.core.cache.StringCache
import com.personalos.app.core.feed.FeedCatalog
import com.personalos.app.data.RetagCandidate
import com.personalos.app.data.TagWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

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
    private val dao: com.personalos.app.data.RetagDao,
    private val tagWriter: TagWriter,
    private val cache: StringCache,
    private val tagger: Tagger,
    /**
     * Declared tags by source for rows the catalog does not know (search
     * sources). Loaded once per run; defaults to none so the catalog-only path
     * needs no sources dependency.
     */
    private val sourceTags: suspend () -> Map<String, Set<String>> = { emptyMap() },
) {
    /**
     * Tags up to `batchSize * maxBatches` items **of one source family** — or of
     * every source when [prefix] is null. Returns how many items gained at least
     * one tag. Safe to call on every launch: once a family's cursor reaches its
     * end, that family costs a single empty query.
     *
     * One cursor **per family** is the point: SMS and the feed backlog no longer
     * share a queue, so a small family finishes in its own time instead of waiting
     * behind the loud one. The tagger itself is unchanged — still one shared
     * implementation and vocabulary.
     */
    private val walk = Mutex()

    suspend fun run(
        prefix: String? = null,
        batchSize: Int = DEFAULT_BATCH_SIZE,
        maxBatches: Int = DEFAULT_MAX_BATCHES,
    ): Int =
        // One family at a time across the process: the families are separate jobs
        // (own cursor, cancel, status), but running all six embedding walks at once
        // saturates a phone. Cursors still keep each family progressing on its own,
        // so serialising execution costs no starvation.
        walk.withLock {
            // Owns its dispatcher: callers include a Main-bound launch block, and
            // this walks a whole family. FeedIngestor.refresh sets the precedent.
            withContext(Dispatchers.IO) {
                // Newest-first: a fresh walk starts just above the family's newest id
                // and moves down, so its recent items are tagged before its older ones.
                val stored = cache.read(cursorKey(prefix))?.value?.toLongOrNull()
                var cursor = stored ?: ((dao.maxId(prefix) ?: return@withContext 0) + 1L)
                var tagged = 0
                var batches = 0
                val extras = runCatching { sourceTags() }.getOrDefault(emptyMap())

                while (batches < maxBatches) {
                    // Cooperative cancellation: a clean kill (WorkManager cancelling
                    // the worker, or a newer run replacing it) stops *between* batches,
                    // with the cursor already persisted, rather than mid-batch. The next
                    // run resumes from exactly there — cancellation costs at most one
                    // batch, never a corrupt walk.
                    coroutineContext.ensureActive()
                    val batch = dao.itemsBefore(prefix, cursor, batchSize)
                    if (batch.isEmpty()) break

                    tagged += tagWriter.writeAll(batch.map { it.toTagInput(extras) })
                    cursor = batch.last().rowId
                    batches++

                    // Persist after each batch so a kill mid-walk resumes, not restarts.
                    cache.write(cursorKey(prefix), cursor.toString(), System.currentTimeMillis())
                }

                if (tagged > 0) {
                    Log.i(TAG, "re-tag with ${tagger.id}: $tagged items tagged (cursor=$cursor)")
                }
                tagged
            }
        }

    private fun cursorKey(prefix: String?) = "retag:cursor:${tagger.id}:r$RECOVERY_VERSION:${prefix ?: "all"}"

    private fun RetagCandidate.toTagInput(sourceTags: Map<String, Set<String>>): Pair<String, TagInput> {
        val isSms = source == SMS_SOURCE

        return ulid to
            TagInput(
                text = "$title\n$content",
                source = if (isSms) Transport.SMS else Transport.RSS,
                // For SMS the title column holds the sender, which drives the
                // personal/promotional priors.
                sender = title.takeIf { isSms },
                // Recover the source's full tag set from the catalog rather than
                // the single `category` column, so a re-tag restores *all* of a
                // source's tags. Sources the catalog never heard of fall back to
                // the source table. SMS rows store an SmsClass name in `category`,
                // which is not a tag, so they declare nothing.
                declaredTags =
                    if (isSms) {
                        emptySet()
                    } else {
                        FeedCatalog.bySource(source)?.tags ?: sourceTags[source].orEmpty()
                    },
            )
    }

    private companion object {
        const val TAG = "Retagger"
        const val SMS_SOURCE = "sms"
        const val DEFAULT_BATCH_SIZE = 200

        /**
         * 200 x 5 = **1000 items per run.** Deliberately bounded: a re-tag runs on
         * launch and must finish quickly, and the cursor means the rest follow on
         * the next runs. Newest-first, so those 1000 are the ones seen most
         * recently.
         */
        const val DEFAULT_MAX_BATCHES = 5

        /**
         * Bump when what a re-tag *recovers* changes without a tagger version
         * bump (r1: source-table fallback for non-catalog sources; **r2: the walk
         * is newest-first**, so an old ascending cursor would be read as a
         * descending one). A new cursor starts a fresh walk; the orphaned old
         * cursor is one tiny prefs row.
         */
        const val RECOVERY_VERSION = 2
    }
}
