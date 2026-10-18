package com.personalos.app.data.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.personalos.app.core.AppContainer

/**
 * Runs a [com.personalos.app.core.rules.RuleBackfill] for one rule off the main
 * thread, as a persisted background job.
 *
 * WorkManager rather than a launch-block coroutine: a backfill can touch five
 * thousand items, and WorkManager survives the app being closed or the process
 * being killed mid-walk. Combined with the per-batch cursor the backfill
 * persists, a crash resumes rather than restarts, and [Result.retry] makes a
 * transient store failure a retry instead of lost work.
 */
class RuleBackfillWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        // No setProgress here: the walk knows scanned-of-window (5000), not
        // scanned-of-what-exists, so a bar would misread. The match count it
        // returns is exact, and that is what the status row shows.
        val ruleId = inputData.getString(KEY_RULE_ID) ?: return Result.failure(workDataOf(KEY_ERROR to "unknown rule"))
        val generation = inputData.getLong(KEY_GENERATION, 0L)
        val container = AppContainer(applicationContext)
        return runCatching { container.ruleBackfill.run(ruleId, generation) }
            .fold(
                onSuccess = { wrote ->
                    Log.i(TAG, "rule backfill $ruleId: +$wrote matches (generation=$generation)")
                    Result.success(workDataOf(KEY_MATCHES to wrote))
                },
                onFailure = { failure ->
                    Log.w(TAG, "rule backfill failed for $ruleId; will retry", failure)
                    Result.retry()
                },
            )
    }

    companion object {
        const val TAG = "RuleBackfillWorker"
        const val KEY_RULE_ID = "ruleId"
        const val KEY_GENERATION = "generation"
        const val KEY_MATCHES = "matches"
    }
}
