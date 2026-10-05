package com.personalos.app.core.rules

import android.util.Log
import com.personalos.app.core.cache.StringCache
import com.personalos.app.data.EventEntity
import com.personalos.app.data.RuleBackfillDao
import com.personalos.app.data.RuleDao
import com.personalos.app.data.RuleEntity
import com.personalos.app.data.RuleItemSeed
import com.personalos.app.data.toRuleItemSeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Re-evaluates **one** rule over items already in the store, so a rule added or
 * edited after an item arrived still reports what it would have caught.
 *
 * Rules are otherwise only evaluated at ingest ([com.personalos.app.data.RuleWriter.writeAll]),
 * so a rule created today never sees yesterday's items, and `Retagger` exists for
 * exactly this reason on the tag side. This is the rule-side counterpart.
 *
 * Three properties, each of which has cost the tag walk in the past:
 *
 *  - **Bounded.** A run looks back at most [DEFAULT_MAX_ITEMS] items, newest
 *    first, so it is a fixed amount of work rather than a scan of the whole
 *    store. A rule will later be able to carry its own start date; that replaces
 *    the window's anchor, not the batching.
 *  - **It resumes.** Progress — the window's top, the cursor and the count — is
 *    persisted after every batch, keyed by `(rule, generation)`, so a process
 *    kill mid-walk continues where it stopped instead of redoing the walk.
 *  - **It is idempotent.** Matches are written `INSERT IGNORE`, so re-evaluating
 *    an item the run already passed — a retry, or an item ingest also evaluated —
 *    cannot duplicate a fire. This is why a resume does not need to know exactly
 *    where it was, only roughly.
 *
 * A **generation** is one request to backfill: a rule add or update (the rule's
 * `updated_at`), or the launch sweep. A run whose generation is already marked
 * done is a no-op, so the launch sweep is cheap after the first time; a new
 * generation — an edit — starts a fresh window from the top.
 */
class RuleBackfill(
    private val dao: RuleBackfillDao,
    private val ruleDao: RuleDao,
    /**
     * Writes this rule's matches over these seeds. Injected rather than taking a
     * `RuleWriter`, so the batching, the window and the resume logic are testable
     * without the full writer and its five DAOs.
     */
    private val writeMatches: suspend (RuleEntity, List<RuleItemSeed>) -> Int,
    private val cache: StringCache,
) {
    /**
     * Backfills [ruleId] at [generation]. Returns the number of matches actually
     * written (new fires; `IGNORE` drops ones already present). Safe to call
     * again at the same generation — it is a no-op once done.
     *
     * A store failure is *not* swallowed: it propagates so the caller (a worker)
     * retries, and the persisted cursor makes the retry a resume. A rule whose
     * condition is unreadable simply writes nothing, via [writeMatches].
     */
    suspend fun run(
        ruleId: String,
        generation: Long,
        maxItems: Int = DEFAULT_MAX_ITEMS,
        batchSize: Int = DEFAULT_BATCH_SIZE,
    ): Int =
        withContext(Dispatchers.IO) {
            val rule =
                runCatching { ruleDao.all() }
                    .getOrDefault(emptyList())
                    .firstOrNull { it.id == ruleId }
                    ?: return@withContext 0

            val doneKey = "rule:backfill:$ruleId:done"
            if (cache.read(doneKey)?.value == generation.toString()) return@withContext 0

            val now = System.currentTimeMillis()
            val prefix = "rule:backfill:$ruleId:$generation"
            val anchored = cache.read("$prefix:top")?.value?.toLongOrNull()
            var cursor: Long
            var processed: Int
            if (anchored == null) {
                cursor = (runCatching { dao.maxId() }.getOrNull() ?: 0L) + 1
                processed = 0
                cache.write("$prefix:top", (cursor - 1).toString(), now)
            } else {
                cursor = cache.read("$prefix:cursor")?.value?.toLongOrNull() ?: (anchored + 1)
                processed = cache.read("$prefix:count")?.value?.toIntOrNull() ?: 0
            }

            var written = 0
            while (processed < maxItems) {
                val take = minOf(batchSize, maxItems - processed)
                val batch: List<EventEntity> = dao.before(cursor, take)
                if (batch.isEmpty()) break
                written += writeMatches(rule, batch.map { it.toRuleItemSeed() })
                cursor = batch.last().id
                processed += batch.size
                // Persist after each batch, so a kill mid-walk resumes.
                cache.write("$prefix:cursor", cursor.toString(), now)
                cache.write("$prefix:count", processed.toString(), now)
                if (batch.size < take) break
            }

            cache.write(doneKey, generation.toString(), now)
            if (written > 0) Log.i(TAG, "backfill ${rule.id}: +$written matches over $processed items")
            written
        }

    private companion object {
        const val TAG = "RuleBackfill"

        /** The window: the most recent items. Retagger's batch size, for symmetry. */
        const val DEFAULT_MAX_ITEMS = 5_000
        const val DEFAULT_BATCH_SIZE = 200
    }
}
