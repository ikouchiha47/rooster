package com.personalos.app.data.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.personalos.app.core.AppContainer

/**
 * Scheduled ingest. Runs the same [com.personalos.app.core.feed.FeedIngestor]
 * path the UI uses, so there is exactly one ingest code path and the worker
 * cannot drift from a manual sync.
 *
 * WorkManager persists periodic work across reboots, so scheduling once at
 * startup is enough.
 */
class FeedSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = AppContainer(applicationContext)
        return runCatching { container.feeds.refresh() }
            .fold(
                onSuccess = { added ->
                    Log.i(TAG, "scheduled sync: +$added items")
                    Result.success(workDataOf(KEY_ADDED to added))
                },
                onFailure = { error ->
                    Log.w(TAG, "scheduled sync failed (attempt $runAttemptCount)", error)
                    // Transient network failures are worth retrying; give up after a few.
                    if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
                },
            )
    }

    companion object {
        const val TAG = "FeedSyncWorker"
        const val KEY_ADDED = "added"
        private const val MAX_ATTEMPTS = 3
    }
}
