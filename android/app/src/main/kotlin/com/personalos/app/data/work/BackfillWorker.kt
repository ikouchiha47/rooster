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
        val container = AppContainer(applicationContext)
        val retagged = runCatching { container.retagger.run() }.getOrDefault(0)
        val places = runCatching { container.placesSeeder.seed() }.getOrDefault(0)
        val parties = runCatching { container.partySeeder.seed() }.getOrDefault(0)
        val sources = runCatching { container.sourceSeeder.seed() }.getOrDefault(0)
        val rules = runCatching { container.ruleSeeder.seed() }.getOrDefault(0)
        val cleaned = runCatching { cleanStopwordedMentions() }.getOrDefault(0)
        val mentions = runCatching { container.mentionWriter.backfill() }.getOrDefault(0)
        Log.i(TAG, "backfill done: +$retagged tags, +$places places, +$parties parties, +$sources sources, +$rules rules, -$cleaned stale mentions, +$mentions mentions")
        return Result.success()
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
    }
}
