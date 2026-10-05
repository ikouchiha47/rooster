package com.personalos.app.data.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.personalos.app.core.AppContainer
import com.personalos.app.core.mention.PlaceIndex
import com.personalos.app.data.AppDatabase
import com.personalos.app.data.cache.PrefsStringCache

/**
 * Launch catch-up as a persisted background job instead of launch-block work.
 *
 * This chain used to run in `MainActivity.lifecycleScope.launch` — which is
 * Main-bound — so the retag walk, the gazetteer parse and the mention backfill
 * (including compiling a ~40k-alternative regex) froze the UI past the ANR
 * timeout on a cold start and the process died. Each step already owns
 * `Dispatchers.IO`, but launch-block coroutines die with the activity;
 * WorkManager survives the app being closed mid-walk, and every step is
 * cursor- or count-gated so a restart resumes instead of redoing.
 *
 * Order matters: the mention backfill's index and lexicon read the tables the
 * seeders fill, so seeds run first (each is a single `COUNT(*)` no-op after
 * the first run).
 */
class BackfillWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val version = inputData.getLong(BackfillVersion.KEY, 0L)
        if (!BackfillVersion.isCurrent(applicationContext, version)) {
            Log.i(TAG, "backfill superseded (v$version)")
            return Result.success()
        }
        val container = AppContainer.get(applicationContext)
        Log.i(TAG, "backfill start")
        // Upgrade the tagger first: if the embedding model is already on disk, the
        // retag below uses the policy tagger, so the store improves on launch.
        // Logged as its own step: loading a 171 MB model is the slow part, and if
        // this is where a run stalls, the log must say so rather than go silent.
        val upgradeStartedAt = System.currentTimeMillis()
        val upgraded = step(false) { container.upgradeTagger() }
        Log.i(TAG, "tagger upgrade: embedding=$upgraded (${System.currentTimeMillis() - upgradeStartedAt}ms)")
        // After the load, drop any model file the active bundle no longer names —
        // a runtime swap self-heals here rather than leaving the old weights.
        val pruned = step(0) { container.pruneModels() }
        Log.i(TAG, "model prune: -$pruned files")
        // The retag is no longer here: each source family is its own RetagWorker job,
        // so a large family cannot hold up a small one.
        val places = step(0) { container.placesSeeder.seed() }
        val parties = step(0) { container.partySeeder.seed() }
        val sources = step(0) { container.sourceSeeder.seed() }
        val rules = step(0) { container.ruleSeeder.seed() }
        val cleaned = step(0) { cleanStopwordedMentions() }
        val mentions = step(0) { container.mentionWriter.backfill() }
        val swept = step(0) { sweepRuleBackfillsOnce() }
        Log.i(TAG, "backfill done: embedding=$upgraded, +$places places, +$parties parties, +$sources sources, +$rules rules, -$cleaned stale mentions, +$mentions mentions, $swept rule backfills")
        return Result.success()
    }

    /**
     * Runs one step, logging and falling back on an ordinary failure but **letting
     * cancellation through**.
     *
     * A bare `runCatching` catches `CancellationException` too, so a clean stop
     * (WorkManager cancelling the worker, or a newer run replacing it) became a
     * misleading "+0 tags" and a dead coroutine carried on. Cancellation is not a
     * failure — it must propagate so the worker is treated as cancelled and the
     * persisted cursor is what the next run resumes from.
     */
    private suspend fun <T> step(
        fallback: T,
        block: suspend () -> T,
    ): T =
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "backfill step failed", e)
            fallback
        }

    /**
     * Once per install/update, enqueue a backfill for every enabled rule so a
     * rule that predates the backfill feature — a bundled seed, most of all —
     * still reports what it would have caught. Gated so it is enqueued once, not
     * on every launch; from then on rule add/edit enqueues. Actual work happens
     * in [RuleBackfillWorker], in the background.
     */
    private suspend fun sweepRuleBackfillsOnce(): Int {
        val cache = PrefsStringCache(applicationContext)
        if (cache.read(RULE_SWEEP_KEY)?.value == RULE_SWEEP_DONE) return 0
        val rules =
            AppDatabase
                .getInstance(applicationContext)
                .ruleDao()
                .all()
                .filter { it.enabled }
        rules.forEach { SyncScheduler.enqueueRuleBackfill(applicationContext, it.id, it.updatedAt ?: it.createdAt) }
        cache.write(RULE_SWEEP_KEY, RULE_SWEEP_DONE, System.currentTimeMillis())
        return rules.size
    }

    /**
     * One-time removal of mention rows the lexicon has since stopworded. Runs
     * once (prefs-gated): the fix only removes matches, so deleting the stale
     * rows is complete with no rescan — affected items keep their remaining
     * mentions, and the backfill walk only visits items with none at all.
     */
    private suspend fun cleanStopwordedMentions(): Int {
        val cache = PrefsStringCache(applicationContext)
        if (cache.read(CLEANUP_KEY)?.value == DONE) return 0
        val deleted =
            AppDatabase
                .getInstance(applicationContext)
                .mentionDao()
                .deleteSurfaces(PlaceIndex.STOPWORDS.toList())
        cache.write(CLEANUP_KEY, DONE, System.currentTimeMillis())
        return deleted
    }

    companion object {
        const val TAG = "BackfillWorker"
        private const val CLEANUP_KEY = "mentions:stopwords-cleaned-v1"
        private const val DONE = "1"

        private const val RULE_SWEEP_KEY = "rules:backfill-swept"
        private const val RULE_SWEEP_DONE = "1"
    }
}
