package com.personalos.app.data.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.personalos.app.core.AppContainer

/**
 * Yearly party-registry refresh. Runs the same [com.personalos.app.data.remote.parties.PartySyncer]
 * path a manual resync uses, so there is exactly one sync code path.
 *
 * Parties change once a decade per country, not once an hour — yearly is
 * plenty, and WorkManager timing is best-effort anyway. Emerging parties
 * between syncs arrive via the bundled seed updates and user additions, not
 * by polling harder.
 */
class PartySyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = AppContainer(applicationContext)
        return runCatching { container.partySync.syncAll() }
            .fold(
                onSuccess = { done ->
                    Log.i(TAG, "scheduled party sync: ${done.size} countries")
                    Result.success(workDataOf(KEY_COUNTRIES to done.size))
                },
                onFailure = { error ->
                    Log.w(TAG, "scheduled party sync failed (attempt $runAttemptCount)", error)
                    if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
                },
            )
    }

    companion object {
        const val TAG = "PartySyncWorker"
        const val KEY_COUNTRIES = "countries"
        private const val MAX_ATTEMPTS = 3
    }
}
