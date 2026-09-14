package com.personalos.app.data.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Owns the periodic background schedules. Idempotent: calling [schedule]
 * repeatedly keeps the existing jobs rather than stacking duplicates.
 *
 * WorkManager persists these, so they survive the app being closed, the screen
 * locking, and a reboot - and it restarts the process to run them. A user
 * **force-stop** cancels them until the next launch, and Doze defers them, so
 * timings are best-effort rather than exact.
 *
 * Two jobs, deliberately separate: ingest is cheap and frequent, enrichment
 * fetches whole article pages and is neither.
 */
object SyncScheduler {
    /** WorkManager's floor for periodic work is 15 minutes. */
    const val DEFAULT_INTERVAL_MINUTES = 60L

    /** Enrichment is far slower per item and not time-critical. */
    const val DEFAULT_ENRICH_INTERVAL_HOURS = 6L

    private const val FEED_UNIQUE_NAME = "feed-sync"
    private const val ENRICH_UNIQUE_NAME = "article-enrich"
    private const val PARTY_UNIQUE_NAME = "party-sync"
    private const val PARTY_MANUAL_NAME = "party-resync-now"
    private const val BACKOFF_MINUTES = 15L

    /** Party lists change per election cycle, not per hour: yearly is plenty. */
    const val PARTY_INTERVAL_DAYS = 365L

    fun schedule(
        context: Context,
        intervalMinutes: Long = DEFAULT_INTERVAL_MINUTES,
        enrichIntervalHours: Long = DEFAULT_ENRICH_INTERVAL_HOURS,
    ) {
        val feedRequest =
            PeriodicWorkRequestBuilder<FeedSyncWorker>(
                intervalMinutes.coerceAtLeast(15L),
                TimeUnit.MINUTES,
            ).setConstraints(
                Constraints
                    .Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            ).setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                BACKOFF_MINUTES,
                TimeUnit.MINUTES,
            ).build()

        WorkManager
            .getInstance(context)
            .enqueueUniquePeriodicWork(
                FEED_UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                feedRequest,
            )

        val enrichRequest =
            PeriodicWorkRequestBuilder<EnrichWorker>(
                enrichIntervalHours.coerceAtLeast(1L),
                TimeUnit.HOURS,
            ).setConstraints(
                Constraints
                    .Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build(),
            ).setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                BACKOFF_MINUTES,
                TimeUnit.MINUTES,
            ).build()

        WorkManager
            .getInstance(context)
            .enqueueUniquePeriodicWork(
                ENRICH_UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                enrichRequest,
            )

        val partyRequest =
            PeriodicWorkRequestBuilder<PartySyncWorker>(
                PARTY_INTERVAL_DAYS,
                TimeUnit.DAYS,
            ).setConstraints(
                Constraints
                    .Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            ).setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                BACKOFF_MINUTES,
                TimeUnit.MINUTES,
            ).build()

        WorkManager
            .getInstance(context)
            .enqueueUniquePeriodicWork(
                PARTY_UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                partyRequest,
            )
    }

    /**
     * Manual party resync: same worker, run once now rather than on the yearly
     * clock. Replaces any pending manual run so rapid taps queue one sync.
     */
    fun resyncPartiesNow(context: Context) {
        val request =
            OneTimeWorkRequestBuilder<PartySyncWorker>()
                .setConstraints(
                    Constraints
                        .Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                ).build()

        WorkManager
            .getInstance(context)
            .enqueueUniqueWork(
                PARTY_MANUAL_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
    }
}
