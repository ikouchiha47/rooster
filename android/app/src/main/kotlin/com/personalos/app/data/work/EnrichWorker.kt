package com.personalos.app.data.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.personalos.app.core.AppContainer

/**
 * Scheduled enrichment. Runs the same [com.personalos.app.data.remote.ArticleEnricher]
 * the rest of the app uses, so there is one code path.
 *
 * Separate from [FeedSyncWorker] on purpose: ingest should never wait on article
 * fetches, and enrichment is far slower per item than reading a feed.
 */
class EnrichWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = AppContainer(applicationContext)
        return runCatching {
            // Which path this is — the direct enricher call, or the enrich→retag
            // workflow — is bound once in the container from the stored flag, so
            // this worker never chooses (ADR 0009 P3).
            container.enrichment.run(
                // The candidates list is the exact denominator, so this is a
                // real fraction. Only here: the other workers know
                // done-or-not, which reads as state with no bar.
                onProgress = { done, total ->
                    setProgress(workDataOf(KEY_PROGRESS_DONE to done, KEY_PROGRESS_TOTAL to total))
                },
            )
        }.fold(
            onSuccess = { enriched ->
                Log.i(TAG, "scheduled enrichment: +$enriched summaries")
                Result.success(workDataOf(KEY_ENRICHED to enriched))
            },
            onFailure = { error ->
                Log.w(TAG, "enrichment batch failed (attempt $runAttemptCount)", error)
                if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure(workDataOf(KEY_ERROR to failureKindName(error)))
            },
        )
    }

    companion object {
        const val TAG = "EnrichWorker"
        const val KEY_ENRICHED = "enriched"
        private const val MAX_ATTEMPTS = 3
    }
}
