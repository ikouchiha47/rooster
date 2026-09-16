package com.personalos.app.debug

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.personalos.app.core.AppContainer

/**
 * Debug-only forced ingest: the same [AppContainer] feed path a scheduled sync
 * uses, but with the payload cache bypassed for this run.
 *
 * Lives in the `debug` source set, so a release APK does not contain it. The
 * `force` parameter itself is part of `FeedIngestor` (a release build must
 * still honour a normal refresh), but this is the only caller that passes
 * `force = true` — in release, nothing can set it.
 */
class ForceSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = AppContainer(applicationContext)
        return runCatching { container.feeds.refresh(force = true) }
            .fold(
                onSuccess = { added ->
                    Log.i(TAG, "forced sync: +$added items")
                    Result.success()
                },
                onFailure = { error ->
                    Log.w(TAG, "forced sync failed", error)
                    Result.failure()
                },
            )
    }

    companion object {
        const val TAG = "ForceSync"
    }
}
