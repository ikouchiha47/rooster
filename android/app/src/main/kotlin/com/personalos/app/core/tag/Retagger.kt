package com.personalos.app.core.tag

import android.util.Log
import com.personalos.app.core.feed.FeedCatalog
import com.personalos.app.data.RetagCandidate
import com.personalos.app.data.TagWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Backfills tags from the **derived backlog** (ADR 0010 §3).
 *
 * An item needs a re-tag when the active tagger has no `tagged_items` marker for
 * it, or when it was enriched after that marker. The store already holds both
 * timestamps, so there is no cursor and no queue table:
 *
 *  - **It converges.** Every successful batch writes a marker (even for an empty
 *    tag set), so those rows drop out of the next query.
 *  - **A new tagger re-tags everything.** The marker is keyed by tagger id, so a
 *    version bump makes the active tagger's set empty and every row a candidate.
 *  - **Enrichment is visible.** `enriched_at > tagged_at` brings an item back
 *    after its body changed, so its tags and vector are computed from the new text.
 *
 * Writes are `INSERT ... IGNORE` on `(item_id, tag, tagger_id)`, so re-running
 * is safe and never duplicates a tag (docs/ARCHITECTURE.md §11.5).
 */
class Retagger(
    private val dao: com.personalos.app.data.RetagDao,
    private val tagWriter: TagWriter,
    private val tagger: Tagger,
    /**
     * Declared tags by source for rows the catalog does not know (search
     * sources). Loaded once per run; defaults to none so the catalog-only path
     * needs no sources dependency.
     */
    private val sourceTags: suspend () -> Map<String, Set<String>> = { emptyMap() },
    /** Append-only telemetry; a no-op when it is off. */
    private val journal: com.personalos.workflow.Journal = com.personalos.workflow.Journal.Noop,
    /** Whole-device energy samples across each batch, when telemetry is on. */
    private val power: com.personalos.app.core.metrics.PowerMeter? = null,
) {
    /**
     * Tags up to `batchSize * maxBatches` items **of one source family** — or of
     * every source when [prefix] is null. Returns how many items gained at least
     * one tag. Safe to call repeatedly: the marker written by each batch removes
     * those rows from the next query.
     *
     * One query **per family** is the point: SMS and the feed backlog no longer
     * share a queue, so a small family finishes in its own time instead of waiting
     * behind the loud one. The tagger itself is unchanged — still one shared
     * implementation and vocabulary.
     */
    suspend fun run(
        prefix: String? = null,
        batchSize: Int = DEFAULT_BATCH_SIZE,
        maxBatches: Int = DEFAULT_MAX_BATCHES,
    ): RetagRun =
        // Families are not serialised here: concurrent walks are bounded once, at
        // the model, by the embedder's semaphore. So a family never holds the model
        // for a whole walk (which starved the small ones), and the CPU limit is the
        // single one that matters.
        //
        // Owns its dispatcher: callers include a Main-bound launch block, and this
        // walks a whole family. FeedIngestor.refresh sets the precedent.
        withContext(Dispatchers.IO) {
            // The marker query is against the *active* tagger. Promote this one
            // first so a fresh version is visible immediately rather than after
            // an ingest path happens to run.
            tagWriter.ensureActive()
            // If a stronger tagger is active (and its model is not loaded here),
            // this rung must not write a parallel, weaker marker set. The recon
            // will retry once the right tagger can be promoted.
            if (!tagWriter.isActive()) return@withContext RetagRun(tagged = 0, exhausted = true)

            var tagged = 0
            var batches = 0
            var exhausted = false
            val extras = runCatching { sourceTags() }.getOrDefault(emptyMap())

            while (batches < maxBatches) {
                // Cooperative cancellation: a clean kill (WorkManager cancelling
                // the worker, or a newer run replacing it) stops *between* batches,
                // with the batch already committed, rather than mid-batch. The next
                // run re-reads the same backlog, so cancellation costs at most one
                // batch of duplicate work.
                coroutineContext.ensureActive()
                val batch = dao.itemsNeedingRetag(tagger.id, prefix, batchSize)
                if (batch.isEmpty()) {
                    exhausted = true
                    break
                }

                val attempt = batches + 1
                val startedAt = System.currentTimeMillis()
                val powerFrom = power?.sample()
                note(
                    com.personalos.workflow.JournalEvent(
                        at = startedAt,
                        host = STAGE_HOST,
                        runId = stageRunId(),
                        node = STAGE_NODE,
                        attempt = attempt,
                        phase = com.personalos.workflow.JournalPhase.START,
                    ),
                )
                val taggedInBatch =
                    try {
                        tagWriter.writeAll(batch.map { it.toTagInput(extras) })
                    } catch (t: Throwable) {
                        val failedAt = System.currentTimeMillis()
                        note(
                            com.personalos.workflow.JournalEvent(
                                at = failedAt,
                                host = STAGE_HOST,
                                runId = stageRunId(),
                                node = STAGE_NODE,
                                attempt = attempt,
                                phase = com.personalos.workflow.JournalPhase.END,
                                status = com.personalos.workflow.NodeStatus.FAILED,
                                durationMs = failedAt - startedAt,
                                error = t.message,
                            ),
                        )
                        throw t
                    }
                tagged += taggedInBatch
                val endedAt = System.currentTimeMillis()
                val powerDelta = powerFrom?.let { from -> power?.sample()?.let { to -> power?.delta(from, to) } }
                note(
                    com.personalos.workflow.JournalEvent(
                        at = endedAt,
                        host = STAGE_HOST,
                        runId = stageRunId(),
                        node = STAGE_NODE,
                        attempt = attempt,
                        phase = com.personalos.workflow.JournalPhase.END,
                        status = com.personalos.workflow.NodeStatus.SUCCEEDED,
                        durationMs = endedAt - startedAt,
                        energyNwh = powerDelta?.energyNwh,
                        chargeUah = powerDelta?.chargeUah,
                        elapsedMs = powerDelta?.elapsedMs,
                    ),
                )
                batches++
                // A short batch is the end of the family; a full one may have more.
                if (batch.size < batchSize) {
                    exhausted = true
                    break
                }
            }

            if (tagged > 0) {
                Log.i(TAG, "re-tag with ${tagger.id}: $tagged items tagged")
            }
            RetagRun(tagged, exhausted)
        }

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
                // the source table. SMS rows store a category id in `category`,
                // which is not a tag, so they declare nothing.
                declaredTags =
                    if (isSms) {
                        emptySet()
                    } else {
                        FeedCatalog.bySource(source)?.tags ?: sourceTags[source].orEmpty()
                    },
            )
    }

    /** Telemetry must never fail a batch: swallow a sink error, keep cancellation. */
    private suspend fun note(event: com.personalos.workflow.JournalEvent) {
        try {
            journal.record(event)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Losing a sample is better than losing the batch.
        }
    }

    private fun stageRunId(): String = "stage:retag:${tagger.id}"

    private companion object {
        const val STAGE_HOST = "stage"
        const val STAGE_NODE = "retag"

        const val TAG = "Retagger"
        const val SMS_SOURCE = "sms"
        const val DEFAULT_BATCH_SIZE = 200

        /**
         * 200 x 2 = **400 items per run.** Kept small so one background run fits a
         * single OS scheduling window and finishes instead of being cut — Android
         * throttles long background jobs. The backlog query re-reads whatever is
         * still pending, newest-first, on the next run.
         */
        const val DEFAULT_MAX_BATCHES = 2
    }
}

/**
 * One bounded re-tag run: how many items gained a tag, and whether the walk reached
 * the end of the family. A worker uses [exhausted] to stop, or to continue the walk
 * in another short run — so each run fits an OS scheduling window.
 */
data class RetagRun(
    val tagged: Int,
    val exhausted: Boolean,
)
