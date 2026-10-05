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
    internal const val BACKFILL_UNIQUE_NAME = "launch-backfill"

    /** Tags every backfill unit (core + one per family) so status can list them all. */
    internal const val BACKFILL_TAG = "backfill"
    private const val RULE_BACKFILL_PREFIX = "rule-backfill:"
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
     * Launch catch-up, as one core job plus **one job per source family**.
     *
     * Versioned, latest wins: every enqueue bumps [BackfillVersion] and stamps each
     * unit with it, and `REPLACE`s the unit under its own name. A unit carrying an
     * older version exits before doing anything — so there is no cancel race, and a
     * superseded run never loads the model. Per family so a large family (feeds)
     * cannot hold up a small one (SMS), and each can be inspected on its own.
     *
     * All units share the `backfill` tag, so status is one query. No network
     * constraint — this is local store work (seeds, upgrade, retag).
     */
    fun enqueueBackfill(context: Context) {
        val version = BackfillVersion.bump(context)
        val workManager = WorkManager.getInstance(context)

        workManager.enqueueUniqueWork(
            BACKFILL_UNIQUE_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<BackfillWorker>()
                .setInputData(androidx.work.workDataOf(BackfillVersion.KEY to version))
                .addTag(BACKFILL_TAG)
                .build(),
        )

        RetagFamilies.ALL.forEach { family ->
            workManager.enqueueUniqueWork(
                RetagFamilies.uniqueName(family),
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<RetagWorker>()
                    .setInputData(
                        androidx.work.workDataOf(
                            RetagWorker.KEY_FAMILY to family,
                            BackfillVersion.KEY to version,
                        ),
                    ).addTag(BACKFILL_TAG)
                    .build(),
            )
        }
    }

    /**
     * Backfill one rule over recent history, once, in the background.
     *
     * Unique per rule and `REPLACE`, so editing a rule supersedes a queued or
     * in-flight run for the older condition rather than both running; the
     * backfill's own cursor still lets a *retry* resume. No network constraint —
     * this is all local store work.
     */
    fun enqueueRuleBackfill(
        context: Context,
        ruleId: String,
        generation: Long,
    ) {
        val request =
            OneTimeWorkRequestBuilder<RuleBackfillWorker>()
                .setInputData(
                    androidx.work.workDataOf(
                        RuleBackfillWorker.KEY_RULE_ID to ruleId,
                        RuleBackfillWorker.KEY_GENERATION to generation,
                    ),
                ).setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    BACKOFF_MINUTES,
                    TimeUnit.MINUTES,
                ).build()

        WorkManager
            .getInstance(context)
            .enqueueUniqueWork(
                "$RULE_BACKFILL_PREFIX$ruleId",
                ExistingWorkPolicy.REPLACE,
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
