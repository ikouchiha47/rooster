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
        return runCatching { container.enricher.enrichBatch() }
            .fold(
                onSuccess = { enriched ->
                    Log.i(TAG, "scheduled enrichment: +$enriched summaries")
                    Result.success(workDataOf(KEY_ENRICHED to enriched))
                },
                onFailure = { error ->
                    Log.w(TAG, "enrichment batch failed (attempt $runAttemptCount)", error)
                    if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
                },
            )
    }

    companion object {
        const val TAG = "EnrichWorker"
        const val KEY_ENRICHED = "enriched"
        private const val MAX_ATTEMPTS = 3
    }
}
