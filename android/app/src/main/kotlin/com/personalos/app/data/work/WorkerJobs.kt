package com.personalos.app.data.work

import androidx.work.Data
import androidx.work.WorkInfo

/**
 * Progress and error keys every worker and the status reader share. One
 * owner, so a worker's `setProgress` and the reader's `progress` can never
 * drift apart.
 */
const val KEY_PROGRESS_DONE = "progress_done"
const val KEY_PROGRESS_TOTAL = "progress_total"

/** The short error kind a worker records when its last attempt gives up. */
const val KEY_ERROR = "error"

/**
 * The short, stable name for a failure: the exception kind, never a message
 * or a stack trace. Recorded in output data so the status row can name what
 * happened without storing anything sensitive.
 */
fun failureKindName(failure: Throwable): String = failure.javaClass.simpleName.ifBlank { "error" }

/**
 * One registered background job: its WorkManager unique-work name, its
 * plain-language label, and — when its output carries a count — which key to
 * read and what to call one and many. A job with no count key (done/not-done
 * work) reports state only.
 *
 * [group] collapses several unique names into one row: the per-rule
 * backfills share a group, every static job is its own group. A new worker
 * appears here as a new spec — the UI maps over specs and never branches.
 */
data class WorkerJobSpec(
    val uniqueName: String,
    val label: String,
    val group: String = uniqueName,
    val countKey: String? = null,
    val singular: String? = null,
    val plural: String? = null,
)

/**
 * What the UI may say about a job. Only states WorkInfo can support:
 * [IDLE] means no work record exists — never run, or a finished one-time job
 * WorkManager has since pruned — and says no more than that.
 */
enum class JobState {
    RUNNING,
    SCHEDULED,
    WAITING,
    SUCCEEDED,
    FAILED,
    STOPPED,
    IDLE,
}

/**
 * One job's observable facts. [count] is the last run's take (a periodic
 * job's output persists while it waits for its next slot, so a scheduled job
 * still reports what it last did); [errorKind] is [KEY_ERROR] from a failed
 * run; progress is present only while running with a reported denominator.
 */
data class JobSnapshot(
    val spec: WorkerJobSpec,
    val state: JobState,
    val retrying: Boolean,
    val progressDone: Int?,
    val progressTotal: Int?,
    val count: Int?,
    val errorKind: String?,
)

/**
 * One job's row of facts from its WorkInfo records. Priority is deliberate:
 * running beats queued (a retry in flight still reads as running), queued
 * beats terminal (a periodic job's old success must not mask that it is
 * scheduled again), and a failure beats an older success.
 */
fun summarizeJob(
    spec: WorkerJobSpec,
    infos: List<WorkInfo>,
): JobSnapshot {
    val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
    if (running != null) {
        val total = running.progress.getIntOrNull(KEY_PROGRESS_TOTAL)?.takeIf { it > 0 }
        val done = running.progress.getIntOrNull(KEY_PROGRESS_DONE)?.takeIf { it >= 0 && total != null }
        return JobSnapshot(spec, JobState.RUNNING, false, done, total, null, null)
    }
    val pending = infos.firstOrNull { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
    if (pending != null) {
        // A periodic job waits ENQUEUED between slots with its last run's
        // output still attached, so the row keeps its take while scheduled.
        val retrying = pending.state == WorkInfo.State.ENQUEUED && pending.runAttemptCount > 0
        val state = if (pending.state == WorkInfo.State.BLOCKED) JobState.WAITING else JobState.SCHEDULED
        return JobSnapshot(spec, state, retrying, null, null, countOf(spec, pending.outputData), null)
    }
    val failed = infos.firstOrNull { it.state == WorkInfo.State.FAILED }
    if (failed != null) {
        return JobSnapshot(spec, JobState.FAILED, false, null, null, null, failed.outputData.getString(KEY_ERROR))
    }
    val succeeded = infos.firstOrNull { it.state == WorkInfo.State.SUCCEEDED }
    if (succeeded != null) {
        return JobSnapshot(spec, JobState.SUCCEEDED, false, null, null, countOf(spec, succeeded.outputData), null)
    }
    if (infos.any { it.state == WorkInfo.State.CANCELLED }) {
        return JobSnapshot(spec, JobState.STOPPED, false, null, null, null, null)
    }
    return JobSnapshot(spec, JobState.IDLE, false, null, null, null, null)
}

private fun countOf(
    spec: WorkerJobSpec,
    output: Data,
): Int? = spec.countKey?.let { key -> output.getIntOrNull(key) }

private fun Data.getIntOrNull(key: String): Int? =
    if (hasKeyWithValueOfType(key, Int::class.javaObjectType)) {
        getInt(key, 0)
    } else {
        null
    }
