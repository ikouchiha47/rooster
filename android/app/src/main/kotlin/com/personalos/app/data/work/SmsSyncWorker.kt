package com.personalos.app.data.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.personalos.app.core.AppContainer
import com.personalos.app.data.SmsIngest

/**
 * Scheduled SMS catch-up. Runs the same suspend entry point
 * ([SmsIngest.ingestNewMessages]) the foreground observer calls, so there is one
 * ingest path and the worker cannot drift from it.
 *
 * Polling, not a broadcast: a manifest `SMS_RECEIVED` receiver is gated behind
 * being the default SMS app on modern Android, whereas reading the provider is
 * already permitted and already implemented.
 *
 * Honest limits: WorkManager's periodic floor is 15 minutes, and Doze/battery
 * optimisation may defer it further, so this is "within about 15 minutes, best
 * effort" rather than instant. With the app force-stopped, nothing runs until
 * the next launch. The foreground observer still exists and gives immediacy
 * while the app is open; this worker only covers the rest.
 */
class SmsSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = AppContainer(applicationContext)
        return runCatching { ingest(container.smsSource) }
            .fold(
                onSuccess = { added ->
                    Log.i(TAG, "scheduled sms sync: +$added messages")
                    Result.success(workDataOf(KEY_ADDED to added))
                },
                onFailure = { error ->
                    Log.w(TAG, "scheduled sms sync failed (attempt $runAttemptCount)", error)
                    if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
                },
            )
    }

    companion object {
        const val TAG = "SmsSyncWorker"
        const val KEY_ADDED = "added"
        private const val MAX_ATTEMPTS = 3

        /**
         * The delegation itself, lifted out of [doWork] so a JVM test can assert
         * the worker calls [SmsIngest.ingestNewMessages] — the same entry point
         * the observer uses — without constructing a Worker.
         */
        internal suspend fun ingest(source: SmsIngest): Int = source.ingestNewMessages()
    }
}
