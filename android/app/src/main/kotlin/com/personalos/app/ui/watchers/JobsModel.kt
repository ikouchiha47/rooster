package com.personalos.app.ui.watchers

import com.personalos.app.core.Chars
import com.personalos.app.data.work.JobSnapshot
import com.personalos.app.data.work.JobState
import com.personalos.app.ui.common.compactNumber

/**
 * Background-job decisions, kept out of the composable so they can be tested
 * without rendering or WorkManager: what a row says, which rows collapse into
 * one, and the order rows are read in.
 *
 * Ordering mirrors why [watchersOrder] works: what is moving now reads first
 * and what is quiet sits last. WorkInfo carries no finish timestamps (that is
 * what a future `job_runs` table is for), so "recently active" is approximated
 * by state priority — running, then queued, then failed, then ok, then idle —
 * never by an invented time.
 *
 * Rows are driven by registration data ([JobSnapshot.spec]): a newly
 * scheduled worker renders from its spec with no new branch here.
 */
data class WorkerRow(
    val key: String,
    val label: String,
    val stateText: String,
    val detailText: String?,
    /** Null means no bar: the worker reports no observable numerator/denominator. */
    val fraction: Float?,
    /** Display priority only: lower reads first. */
    val rank: Int,
)

/** One row per snapshot, except snapshots sharing a group collapse into one aggregate row. */
fun workerRows(snapshots: List<JobSnapshot>): List<WorkerRow> =
    snapshots
        .groupBy { it.spec.group }
        .map { (group, grouped) ->
            if (grouped.size == 1) workerRowOf(grouped.single()) else aggregateWorkerRow(group, grouped)
        }

/** `N JOBS · M RUNNING` — what the section owns. Counts rows, not snapshots. */
fun workersHeader(rows: List<WorkerRow>): String {
    val running = rows.count { it.rank == RANK_RUNNING }
    return "${compactNumber(rows.size)} JOBS ${Chars.MIDDLE_DOT} ${compactNumber(running)} RUNNING"
}

/** The section note: running work first, then failures, otherwise nothing. */
fun workersNote(rows: List<WorkerRow>): String? {
    val running = rows.count { it.rank == RANK_RUNNING }
    if (running > 0) return "$running running"
    val failed = rows.count { it.rank == RANK_FAILED }
    if (failed > 0) return "$failed failed"
    return null
}

/** Running first, quiet last; ties read in label order. */
fun workersOrder(rows: List<WorkerRow>): List<WorkerRow> = rows.sortedWith(compareBy<WorkerRow> { it.rank }.thenBy { it.label.lowercase() })

/** The honest words for a state: only what WorkInfo can support, nothing more. */
fun jobStateText(
    state: JobState,
    retrying: Boolean,
): String =
    when (state) {
        JobState.RUNNING -> "running"
        JobState.SCHEDULED -> if (retrying) "retrying" else "scheduled"
        JobState.WAITING -> "waiting"
        JobState.SUCCEEDED -> "last run ok"
        JobState.FAILED -> "last run failed"
        JobState.STOPPED -> "stopped"
        JobState.IDLE -> "idle"
    }

/**
 * Done over total, or null when there is no meaningful denominator. Clamped:
 * an over-reporting worker pins at full rather than drawing past its track.
 */
fun progressFraction(
    done: Int?,
    total: Int?,
): Float? {
    if (done == null || total == null || total <= 0 || done < 0) return null
    return (done.toFloat() / total).coerceIn(0f, 1f)
}

/** `+3 items`, `+1 item`: a zero or missing count is not an outcome. */
fun outcomeText(
    count: Int?,
    singular: String?,
    plural: String?,
): String? {
    if (count == null || singular == null || plural == null || count <= 0) return null
    return "+$count ${if (count == 1) singular else plural}"
}

/** `3 of 20 summaries`: the running line for a worker with observable progress. */
fun progressText(
    done: Int,
    total: Int,
    plural: String?,
): String = if (plural == null) "$done of $total" else "$done of $total $plural"

/** A single snapshot's row. */
fun workerRowOf(snapshot: JobSnapshot): WorkerRow {
    val spec = snapshot.spec
    val detail =
        when (snapshot.state) {
            JobState.RUNNING ->
                snapshot.progressDone?.let { done ->
                    snapshot.progressTotal?.let { total -> progressText(done, total, spec.plural) }
                }
            JobState.SCHEDULED, JobState.WAITING, JobState.SUCCEEDED ->
                outcomeText(snapshot.count, spec.singular, spec.plural)
            JobState.FAILED -> snapshot.errorKind
            JobState.STOPPED, JobState.IDLE -> null
        }
    return WorkerRow(
        key = spec.uniqueName,
        label = spec.label,
        stateText = jobStateText(snapshot.state, snapshot.retrying),
        detailText = detail,
        fraction =
            if (snapshot.state == JobState.RUNNING) {
                progressFraction(snapshot.progressDone, snapshot.progressTotal)
            } else {
                null
            },
        rank = rankOf(snapshot.state),
    )
}

/** Several snapshots in one group (the per-rule backfills) read as one row. */
fun aggregateWorkerRow(
    group: String,
    snapshots: List<JobSnapshot>,
): WorkerRow {
    val label = snapshots.first().spec.label
    val running = snapshots.filter { it.state == JobState.RUNNING }
    val pending = snapshots.filter { it.state == JobState.SCHEDULED || it.state == JobState.WAITING }
    val failed = snapshots.filter { it.state == JobState.FAILED }
    val ok = snapshots.filter { it.state == JobState.SUCCEEDED }
    val single = snapshots.singleOrNull()
    return when {
        running.isNotEmpty() ->
            WorkerRow(
                key = "group:$group",
                label = label,
                stateText = "running",
                detailText =
                    if (running.size == 1) {
                        runningDetail(running.single())
                    } else {
                        "${running.size} running"
                    },
                fraction = single?.let { progressFraction(it.progressDone, it.progressTotal) },
                rank = RANK_RUNNING,
            )
        pending.isNotEmpty() ->
            WorkerRow(
                key = "group:$group",
                label = label,
                stateText = "scheduled",
                detailText =
                    if (pending.size == 1) {
                        val one = pending.single()
                        outcomeText(one.count, one.spec.singular, one.spec.plural)
                    } else {
                        "${pending.size} queued"
                    },
                fraction = null,
                rank = RANK_ACTIVE,
            )
        failed.isNotEmpty() ->
            WorkerRow(
                key = "group:$group",
                label = label,
                stateText = "last run failed",
                detailText = failed.singleOrNull()?.errorKind ?: "${failed.size} failed",
                fraction = null,
                rank = RANK_FAILED,
            )
        ok.isNotEmpty() -> {
            val total = ok.mapNotNull { it.count }.takeIf { it.isNotEmpty() }?.sum()
            WorkerRow(
                key = "group:$group",
                label = label,
                stateText = "last run ok",
                detailText = outcomeText(total, snapshots.first().spec.singular, snapshots.first().spec.plural),
                fraction = null,
                rank = RANK_OK,
            )
        }
        snapshots.any { it.state == JobState.STOPPED } ->
            WorkerRow(
                key = "group:$group",
                label = label,
                stateText = "stopped",
                detailText = null,
                fraction = null,
                rank = RANK_STOPPED,
            )
        else ->
            WorkerRow(
                key = "group:$group",
                label = label,
                stateText = "idle",
                detailText = null,
                fraction = null,
                rank = RANK_IDLE,
            )
    }
}

private fun runningDetail(snapshot: JobSnapshot): String? =
    snapshot.progressDone?.let { done ->
        snapshot.progressTotal?.let { total -> progressText(done, total, snapshot.spec.plural) }
    }

private fun rankOf(state: JobState): Int =
    when (state) {
        JobState.RUNNING -> RANK_RUNNING
        JobState.SCHEDULED, JobState.WAITING -> RANK_ACTIVE
        JobState.FAILED -> RANK_FAILED
        JobState.SUCCEEDED -> RANK_OK
        JobState.STOPPED -> RANK_STOPPED
        JobState.IDLE -> RANK_IDLE
    }

private const val RANK_RUNNING = 0
private const val RANK_ACTIVE = 1
private const val RANK_FAILED = 2
private const val RANK_OK = 3
private const val RANK_STOPPED = 4
private const val RANK_IDLE = 5
