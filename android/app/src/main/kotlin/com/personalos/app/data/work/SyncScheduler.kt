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
 * Background jobs, deliberately separate: ingest is cheap and frequent, enrichment
 * fetches whole article pages and is neither, and retagging burns CPU on one model.
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

    private const val FEED_UNIQUE_NAME = "feed-sync"
    private const val TAG = "SyncScheduler"

    private const val PARTY_UNIQUE_NAME = "party-sync"
    private const val SMS_UNIQUE_NAME = "sms-sync"
    private const val PARTY_MANUAL_NAME = "party-resync-now"
    const val BACKFILL_UNIQUE_NAME = "launch-backfill"

    /** Tags the backfill job so status can find it. */
    internal const val BACKFILL_TAG = "backfill"

    const val RULE_BACKFILL_PREFIX = "rule-backfill:"

    /** The group every per-rule backfill row collapses into on the status screen. */
    const val RULE_BACKFILL_GROUP = "rule-backfill"
    private const val BACKOFF_MINUTES = 15L

    /** Party lists change per election cycle, not per hour: yearly is plenty. */
    const val PARTY_INTERVAL_DAYS = 365L

    /**
     * Every statically scheduled job, as status-screen specs. The single
     * owner of "which background workers exist": the status screen renders
     * this list, so scheduling a new worker surfaces it with no screen edit.
     *
     * Drains contribute both their fast job and their recon job, derived from
     * [DrainSpec] so names, groups and count keys cannot drift.
     */
    fun scheduledJobs(): List<WorkerJobSpec> =
        listOf(
            WorkerJobSpec(FEED_UNIQUE_NAME, "Feed sync", countKey = FeedSyncWorker.KEY_ADDED, singular = "item", plural = "items"),
            WorkerJobSpec(SMS_UNIQUE_NAME, "SMS sync", countKey = SmsSyncWorker.KEY_ADDED, singular = "message", plural = "messages"),
            WorkerJobSpec(PARTY_UNIQUE_NAME, "Party lists", countKey = PartySyncWorker.KEY_COUNTRIES, singular = "country", plural = "countries"),
            WorkerJobSpec(BACKFILL_UNIQUE_NAME, "Launch backfill"),
        ) + Drains.ALL.flatMap { listOf(it.workerSpec(), it.reconSpec()) }

    /** The unique-work name for one rule's backfill. */
    fun ruleBackfillName(ruleId: String): String = "$RULE_BACKFILL_PREFIX$ruleId"

    /**
     * One rule's backfill as a status-screen spec. Same group for every
     * rule, so the screen collapses them into a single "Rule backfill" row.
     */
    fun ruleBackfillSpec(ruleId: String): WorkerJobSpec =
        WorkerJobSpec(
            uniqueName = ruleBackfillName(ruleId),
            label = "Rule backfill",
            group = RULE_BACKFILL_GROUP,
            countKey = RuleBackfillWorker.KEY_MATCHES,
            singular = "match",
            plural = "matches",
        )

    fun schedule(
        context: Context,
        intervalMinutes: Long = DEFAULT_INTERVAL_MINUTES,
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

        // Remove the pre-ADR 6h periodic enrichment job. The new fast drain has
        // its own name, so that legacy writer must not stay queued (it would make
        // the recon see an ENQUEUED job forever).
        WorkManager.getInstance(context).cancelUniqueWork("article-enrich")

        // Each drain owns its periodic recon. The recon only checks depth and
        // nudges the fast job; it never processes an item (ADR 0010 §7).
        Drains.ALL.forEach { DrainScheduler.android(context, it).ensurePeriodic() }

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
     * Launch catch-up, one persisted backfill job.
     *
     * Versioned, latest wins: every enqueue bumps [BackfillVersion] and stamps the
     * job with it, and `REPLACE`s it under its own name. A job carrying an older
     * version exits before doing anything — so there is no cancel race, and a
     * superseded run never loads the model.
     *
     * A **forced** backfill is also the tagger-version / manual-retag trigger: it
     * starts the fast retag drain, which is the single processor. No network
     * constraint — this is local store work (seeds, upgrade, retag).
     */
    fun enqueueBackfill(
        context: Context,
        force: Boolean = false,
    ) {
        // Bump the version only when *forced*. Otherwise KEEP and reuse the current
        // version, so a routine launch does not supersede an in-flight retag — that
        // supersede churn is what kept cancelling the walk before it finished.
        val version =
            if (force) BackfillVersion.bump(context) else (BackfillVersion.current(context) ?: BackfillVersion.bump(context))
        val policy = if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        val workManager = WorkManager.getInstance(context)

        workManager.enqueueUniqueWork(
            BACKFILL_UNIQUE_NAME,
            policy,
            OneTimeWorkRequestBuilder<BackfillWorker>()
                .setInputData(androidx.work.workDataOf(BackfillVersion.KEY to version))
                .addTag(BACKFILL_TAG)
                .build(),
        )
        // A forced backfill is the tagger-version / manual-retag trigger. The
        // fast drain is the single processor; there is no second retag worker.
        if (force) enqueueRetagNow(context)
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
                ruleBackfillName(ruleId),
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

    /**
     * Start one run of a registered workflow (ADR 0009).
     *
     * Unique per workflow id and `REPLACE`, so a newer trigger supersedes a
     * queued or in-flight run — the run's own `RunStore` still lets the new one
     * skip what already completed. The `runId` is fixed in the request, so a
     * WorkManager retry resumes the same run instead of starting a new one.
     */

    fun enqueueWorkflow(
        context: Context,
        workflowId: String,
    ) {
        val request =
            OneTimeWorkRequestBuilder<com.personalos.app.data.workflow.WorkflowRunWorker>()
                .setInputData(
                    androidx.work.workDataOf(
                        com.personalos.app.data.workflow.WorkflowRunWorker.KEY_WORKFLOW to workflowId,
                        com.personalos.app.data.workflow.WorkflowRunWorker.KEY_RUN_ID to "run-${System.currentTimeMillis()}",
                    ),
                ).setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    BACKOFF_MINUTES,
                    TimeUnit.MINUTES,
                ).build()

        WorkManager
            .getInstance(context)
            .enqueueUniqueWork(
                "workflow:$workflowId",
                // KEEP, not REPLACE. A run is long and resumable, so asking again
                // must not cancel the one in flight — that is how a healthy retag
                // was being killed by a second trigger. A forced restart is an
                // explicit cancel followed by an enqueue, not a side effect.
                ExistingWorkPolicy.KEEP,
                request,
            )
    }

    /**
     * Enrichment now. Coalesced under the drain's one unique name, so a feed and
     * an SMS sync landing together start one batch, not two.
     *
     * Sync only ever starts enrichment (ADR 0010 §7); retagging is discovered
     * later from the store and nudged by its own recon.
     */
    fun enqueueEnrichment(context: Context) {
        DrainScheduler.android(context, Drains.ENRICHMENT).enqueueNow()
    }

    /** A tagger-version change or a manual retag: start the fast drain now. */
    fun enqueueRetagNow(context: Context) {
        DrainScheduler.android(context, Drains.RETAG).enqueueNow()
    }
}
