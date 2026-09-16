package com.personalos.app.data.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
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
    /**
     * Sync-bucket width in minutes: Today's News rows group by ingest run under
     * separators this wide. Single owner — the scheduler default below and the
     * News bucket helper both reference this, so the buckets never drift from
     * the schedule that fills them.
     */
    const val SYNC_GROUP_MINUTES = 60L

    /** WorkManager's floor for periodic work is 15 minutes. */
    const val MIN_INTERVAL_MINUTES = 15L

    /** Default feed cadence: the bucket width that groups Today's News. */
    const val DEFAULT_INTERVAL_MINUTES = SYNC_GROUP_MINUTES

    /** Enrichment is far slower per item and not time-critical. */
    const val DEFAULT_ENRICH_INTERVAL_HOURS = 6L

    private const val FEED_UNIQUE_NAME = "feed-sync"
    private const val ENRICH_UNIQUE_NAME = "article-enrich"
    private const val PARTY_UNIQUE_NAME = "party-sync"
    private const val SMS_UNIQUE_NAME = "sms-sync"
    private const val PARTY_MANUAL_NAME = "party-resync-now"
    private const val BACKFILL_UNIQUE_NAME = "launch-backfill"
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

        // Background SMS catch-up. Polling, not a broadcast: reading the
        // provider is already permitted, whereas a manifest SMS_RECEIVED
        // receiver is gated behind being the default SMS app. The foreground
        // observer in SmsSource stays — this is an addition, not a replacement:
        // the observer gives immediacy while the app is open, this worker
        // covers the rest.
        //
        // Honest limits: WorkManager's periodic floor is 15 minutes and
        // Doze/battery optimisation may defer it further, so this is "within
        // about 15 minutes, best effort" rather than instant; with the app
        // force-stopped nothing runs until the next launch.
        WorkManager
            .getInstance(context)
            .enqueueUniquePeriodicWork(
                SMS_UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                smsSyncRequest(),
            )
    }

    /**
     * The SMS poll. Built apart from [schedule] so the two properties that are
     * deliberate here — the 15-minute floor and the *absence* of a network
     * constraint — are testable without a running WorkManager.
     *
     * No network constraint: reading the provider and writing to Room is
     * entirely local, so requiring CONNECTED would make a background SMS
     * catch-up fail on an offline device for no reason. That is a deliberate
     * difference from the feed request above, not an omission.
     */
    internal fun smsSyncRequest(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<SmsSyncWorker>(
            MIN_INTERVAL_MINUTES,
            TimeUnit.MINUTES,
        ).setBackoffCriteria(
            BackoffPolicy.EXPONENTIAL,
            BACKOFF_MINUTES,
            TimeUnit.MINUTES,
        ).build()

    /**
     * Launch catch-up, run once per install/update rather than stacked: KEEP
     * means a second launch while one backfill runs is a no-op, and the chain
     * inside is cursor-gated so any restart resumes. No network constraint —
     * this is local store work (seeds, retag, mention backfill).
     */
    fun enqueueBackfillOnce(context: Context) {
        val request = OneTimeWorkRequestBuilder<BackfillWorker>().build()

        WorkManager
            .getInstance(context)
            .enqueueUniqueWork(
                BACKFILL_UNIQUE_NAME,
                ExistingWorkPolicy.KEEP,
                request,
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
