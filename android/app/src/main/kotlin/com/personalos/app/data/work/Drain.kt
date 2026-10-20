package com.personalos.app.data.work

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.personalos.app.core.AppContainer
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * One drain's identity and schedule (ADR 0010 §5).
 *
 * [batch] is the bounded unit of work; [chainDelaySeconds] is how soon the next
 * batch runs while the backlog remains; [reconMinutes] is the low-frequency
 * safety net that only checks depth and nudges the fast job.
 */
data class DrainSpec(
    val uniqueName: String,
    val label: String,
    val group: String = uniqueName,
    val batch: Int,
    val chainDelaySeconds: Long,
    val reconMinutes: Long?,
    val network: NetworkType,
    val countKey: String? = null,
    val singular: String? = null,
    val plural: String? = null,
    /** The fast worker class; the recon worker is always [DrainReconWorker]. */
    val workerClass: Class<out ListenableWorker> = DrainWorker::class.java,
) {
    /** A periodic job must never share a unique name with the one-time drain. */
    val reconUniqueName: String get() = "$uniqueName-recon"

    /** Registration data for Watchers — one row shape, no screen branch. */
    fun workerSpec(): WorkerJobSpec = WorkerJobSpec(uniqueName, label, group, countKey, singular, plural)

    /** The recon job's registration; it carries no take, only state. */
    fun reconSpec(): WorkerJobSpec = WorkerJobSpec(reconUniqueName, label, group)
}

/** One bounded drain step: what it did, and whether another batch is warranted. */
data class DrainOutcome(
    val processed: Int,
    val more: Boolean,
)

/**
 * The stage-specific half of a drain. The shared host owns scheduling, progress,
 * retry and chaining, so adding a drain is a spec + this interface — not another
 * worker (ADR 0010 §5).
 */
interface DrainTask {
    suspend fun step(): DrainOutcome

    /** Current depth of the stage's backlog query. */
    suspend fun backlog(): Int
}

/**
 * The app's drains, as data. Names, labels and count keys live here so the
 * scheduler, the worker hosts and Watchers cannot drift.
 */
object Drains {
    /** The input key naming the drain a worker run belongs to. */
    const val KEY = "drain"

    /** Output key used when a spec has no count key. */
    const val KEY_PROCESSED = "processed"

    /** Recon output: the depth it observed, not a take. */
    const val KEY_BACKLOG = "backlog"

    val ENRICHMENT =
        DrainSpec(
            uniqueName = "article-enrich-now",
            label = "Enrichment",
            group = "enrichment",
            batch = 40,
            chainDelaySeconds = 10 * 60,
            reconMinutes = 30,
            network = NetworkType.CONNECTED,
            countKey = EnrichWorker.KEY_ENRICHED,
            singular = "summary",
            plural = "summaries",
            workerClass = EnrichWorker::class.java,
        )

    val RETAG =
        DrainSpec(
            uniqueName = "retag-dispatch",
            label = "Retag",
            group = "retag",
            batch = 200,
            chainDelaySeconds = 30,
            reconMinutes = 30,
            network = NetworkType.NOT_REQUIRED,
            countKey = RetagDispatchWorker.KEY_TAGGED,
            singular = "item",
            plural = "items",
            workerClass = RetagDispatchWorker::class.java,
        )

    val ALL: List<DrainSpec> = listOf(ENRICHMENT, RETAG)

    fun byKey(key: String): DrainSpec? = ALL.firstOrNull { it.uniqueName == key }
}

/**
 * The WorkManager operations a [DrainScheduler] needs, extracted so the
 * enqueue policy is testable without a device or a WorkManager singleton.
 */
data class DrainWorkState(
    val state: WorkInfo.State,
    val runAttemptCount: Int,
)

interface DrainQueue {
    fun states(uniqueName: String): List<DrainWorkState>

    fun enqueue(
        uniqueName: String,
        policy: ExistingWorkPolicy,
        spec: DrainSpec,
        delaySeconds: Long,
    )

    fun enqueuePeriodic(
        uniqueName: String,
        policy: ExistingPeriodicWorkPolicy,
        spec: DrainSpec,
        minutes: Long,
    )
}

private class WorkManagerDrainQueue(
    private val manager: WorkManager,
) : DrainQueue {
    override fun states(uniqueName: String): List<DrainWorkState> =
        runCatching {
            manager
                .getWorkInfosForUniqueWork(uniqueName)
                .get()
                .map { DrainWorkState(it.state, it.runAttemptCount) }
        }.onFailure { Log.w(TAG, "could not read work state for $uniqueName", it) }
            .getOrDefault(emptyList())

    override fun enqueue(
        uniqueName: String,
        policy: ExistingWorkPolicy,
        spec: DrainSpec,
        delaySeconds: Long,
    ) {
        val builder =
            OneTimeWorkRequest
                .Builder(spec.workerClass)
                .setInputData(workDataOf(Drains.KEY to spec.uniqueName))
                .setInitialDelay(delaySeconds.coerceAtLeast(0L), TimeUnit.SECONDS)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    spec.chainDelaySeconds.coerceAtLeast(MIN_BACKOFF_SECONDS),
                    TimeUnit.SECONDS,
                )
        if (spec.network != NetworkType.NOT_REQUIRED) {
            builder.setConstraints(
                Constraints
                    .Builder()
                    .setRequiredNetworkType(spec.network)
                    .build(),
            )
        }
        manager.enqueueUniqueWork(uniqueName, policy, builder.build())
    }

    override fun enqueuePeriodic(
        uniqueName: String,
        policy: ExistingPeriodicWorkPolicy,
        spec: DrainSpec,
        minutes: Long,
    ) {
        val request =
            PeriodicWorkRequestBuilder<DrainReconWorker>(
                minutes.coerceAtLeast(MIN_PERIODIC_MINUTES),
                TimeUnit.MINUTES,
            ).setInputData(workDataOf(Drains.KEY to spec.uniqueName))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
                .build()
        manager.enqueueUniquePeriodicWork(uniqueName, policy, request)
    }

    private companion object {
        const val TAG = "DrainQueue"
    }
}

/**
 * Owns one drain's unique-work names and enqueue policies (ADR 0010 §5).
 *
 * [enqueueNow] replaces a queued/failed run but leaves a **running** batch alone;
 * [enqueueFollowUp] appends after the current run so the chain cannot cancel
 * itself; [enqueueIfIdle] is the recon's only write and refuses to touch a job
 * that is already ENQUEUED, RUNNING or BLOCKED.
 */
class DrainScheduler(
    private val spec: DrainSpec,
    private val queue: DrainQueue,
) {
    constructor(context: Context, spec: DrainSpec) : this(
        spec,
        WorkManagerDrainQueue(WorkManager.getInstance(context.applicationContext)),
    )

    fun enqueueNow() {
        val policy =
            if (queue.states(spec.uniqueName).any { it.state == WorkInfo.State.RUNNING }) {
                ExistingWorkPolicy.KEEP
            } else {
                ExistingWorkPolicy.REPLACE
            }
        enqueue(delaySeconds = 0L, policy = policy)
    }

    /** The next batch while the backlog remains. Append, never replace the runner. */
    fun enqueueFollowUp() {
        enqueue(delaySeconds = spec.chainDelaySeconds, policy = ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    /**
     * The recon's nudge: enqueue the fast drain only when no copy is already
     * ENQUEUED, RUNNING or BLOCKED. The uniqueness policy is a second guard
     * against a race between the check and the enqueue.
     */
    fun enqueueIfIdle() {
        val states = queue.states(spec.uniqueName)
        // A running batch, a blocked job, or a first attempt already queued is
        // work in hand: never add a second processor.
        val inHand =
            states.any {
                it.state == WorkInfo.State.RUNNING ||
                    it.state == WorkInfo.State.BLOCKED ||
                    (it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount == 0)
            }
        if (inHand) return
        // An ENQUEUED job with attempts behind it is a stale backoff (the old
        // retag row reached attempt 9). It is not "in hand", but KEEP would leave
        // it stuck forever, so replace only that case. A race-created fresh job
        // is protected by KEEP.
        val stale = states.any { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0 }
        enqueue(delaySeconds = 0L, policy = if (stale) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP)
    }

    /** The low-frequency safety net: a worker that only checks backlog and nudges. */
    fun ensurePeriodic() {
        val minutes = spec.reconMinutes ?: return
        queue.enqueuePeriodic(
            spec.reconUniqueName,
            ExistingPeriodicWorkPolicy.KEEP,
            spec,
            minutes,
        )
    }

    private fun enqueue(
        delaySeconds: Long,
        policy: ExistingWorkPolicy,
    ) {
        queue.enqueue(spec.uniqueName, policy, spec, delaySeconds)
    }

    companion object {
        fun android(
            context: Context,
            spec: DrainSpec,
        ): DrainScheduler =
            DrainScheduler(
                spec,
                WorkManagerDrainQueue(WorkManager.getInstance(context.applicationContext)),
            )
    }
}

/**
 * Shared drain host: one bounded step, honest progress, chain-if-more and a
 * bounded retry/failure mapping. A stage worker only supplies its spec.
 */
open class DrainWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    protected open fun specFor(key: String): DrainSpec? = Drains.byKey(key)

    override suspend fun doWork(): Result {
        // A pre-upgrade queued host may carry no drain key; the concrete hosts
        // still know their spec, so it runs instead of failing once on upgrade.
        val key = inputData.getString(Drains.KEY)
        val spec = specFor(key ?: "") ?: return Result.failure()
        val task = AppContainer.get(applicationContext).drainTasks[spec.uniqueName] ?: return Result.failure()
        return runDrain(spec, task)
    }
}

/**
 * The recon host. It **never processes an item**: it reads depth and, when there
 * is work, asks the scheduler to start the single fast drain if idle.
 */
class DrainReconWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val key = inputData.getString(Drains.KEY) ?: return Result.failure()
        val spec = Drains.byKey(key) ?: return Result.failure()
        val task = AppContainer.get(applicationContext).drainTasks[spec.uniqueName] ?: return Result.failure()
        return try {
            val backlog = task.backlog()
            setProgress(workDataOf(KEY_PROGRESS_DONE to 0, KEY_PROGRESS_TOTAL to backlog))
            if (backlog > 0) DrainScheduler.android(applicationContext, spec).enqueueIfIdle()
            Result.success(workDataOf(Drains.KEY_BACKLOG to backlog))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "drain recon failed (${spec.uniqueName}, attempt $runAttemptCount)", e)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure(workDataOf(KEY_ERROR to failureKindName(e)))
        }
    }

    private companion object {
        const val TAG = "DrainRecon"
    }
}

/**
 * The shared body. Progress is batch-level and honest: processed of processed +
 * remaining. A failure retries a bounded number of times and then fails with the
 * exception kind only (never a message or stack).
 */
internal suspend fun CoroutineWorker.runDrain(
    spec: DrainSpec,
    task: DrainTask,
): Result =
    try {
        val outcome = task.step()
        val remaining = runCatching { task.backlog() }.getOrDefault(0)
        setProgress(
            workDataOf(
                KEY_PROGRESS_DONE to outcome.processed,
                KEY_PROGRESS_TOTAL to (outcome.processed + remaining),
            ),
        )
        if (outcome.more) {
            DrainScheduler.android(applicationContext, spec).enqueueFollowUp()
        }
        Result.success(workDataOf((spec.countKey ?: Drains.KEY_PROCESSED) to outcome.processed))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "drain failed (${spec.uniqueName}, attempt $runAttemptCount)", e)
        if (runAttemptCount < MAX_ATTEMPTS) {
            Result.retry()
        } else {
            Result.failure(workDataOf(KEY_ERROR to failureKindName(e)))
        }
    }

private const val MAX_ATTEMPTS = 3
private const val TAG = "Drain"
private const val MIN_PERIODIC_MINUTES = 15L
private const val MIN_BACKOFF_SECONDS = 10L
private const val BACKOFF_MINUTES = 15L
