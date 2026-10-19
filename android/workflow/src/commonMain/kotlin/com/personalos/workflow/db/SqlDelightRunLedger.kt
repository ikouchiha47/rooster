package com.personalos.workflow.db

import com.personalos.workflow.Attempt
import com.personalos.workflow.NodeId
import com.personalos.workflow.NodeStatus
import com.personalos.workflow.RunLedger
import com.personalos.workflow.RunState
import com.personalos.workflow.RunStatus
import com.personalos.workflow.foldStatus

/**
 * The durable [RunLedger] (ADR 0010): a `runs` row for identity + lease, and one
 * `node_attempts` row per attempt, written **before** the step runs.
 *
 * Claim, take-over, release and the outcome transition are each a single statement
 * guarded by the row itself — `INSERT OR IGNORE` for the claim, `owner`/`lease_expires_at`
 * for ownership, `version` for OCC — so no lock is needed and two hosts cannot both
 * drive one run. The in-memory ledger is the contract oracle; `RunLedgerAgreementTest`
 * checks the two agree.
 */
class SqlDelightRunLedger(
    private val database: WorkflowDatabase,
) : RunLedger {
    override suspend fun claim(
        runId: String,
        workflowId: String,
        definitionVersion: Int,
        owner: String,
        now: Long,
        leaseMillis: Long,
    ): Boolean =
        database.transactionWithResult {
            database.runQueries.claimInsert(runId, workflowId, definitionVersion.toLong(), owner, now + leaseMillis, now)
            if (database.runQueries.runChanges().executeAsOne() > 0L) {
                true
            } else {
                database.runQueries.takeOver(owner, now + leaseMillis, runId, now)
                database.runQueries.runChanges().executeAsOne() > 0L
            }
        }

    override suspend fun release(
        runId: String,
        owner: String,
        now: Long,
    ): Boolean =
        database.transactionWithResult {
            database.runQueries.release(runId, owner)
            database.runQueries.runChanges().executeAsOne() > 0L
        }

    override suspend fun run(runId: String): RunState? =
        database.runQueries
            .selectRun(runId)
            .executeAsOneOrNull()
            ?.let {
                RunState(
                    runId = it.run_id,
                    workflowId = it.workflow_id,
                    definitionVersion = it.definition_version.toInt(),
                    owner = it.owner,
                    leaseExpiresAt = it.lease_expires_at,
                    startedAt = it.started_at,
                )
            }

    override suspend fun nextAttempt(
        runId: String,
        node: NodeId,
    ): Int =
        database.nodeAttemptQueries
            .nextAttempt(runId, node.value)
            .executeAsOne()
            .toInt()

    override suspend fun beginAttempt(attempt: Attempt) {
        database.nodeAttemptQueries.beginAttempt(
            attempt.runId,
            attempt.node.value,
            attempt.attempt.toLong(),
            attempt.status.name,
            attempt.startedAt,
            attempt.invokedBy,
            attempt.correlationId,
            attempt.inputs,
        )
    }

    override suspend fun finishAttempt(
        runId: String,
        node: NodeId,
        attempt: Int,
        expectedVersion: Long,
        status: NodeStatus,
        outputs: String?,
        now: Long,
    ): Boolean =
        database.transactionWithResult {
            database.nodeAttemptQueries.finishAttempt(
                status.name,
                outputs,
                now,
                runId,
                node.value,
                attempt.toLong(),
                expectedVersion,
            )
            database.nodeAttemptQueries.attemptChanges().executeAsOne() > 0L
        }

    override suspend fun attempts(runId: String): List<Attempt> =
        database.nodeAttemptQueries
            .selectAttempts(runId)
            .executeAsList()
            .map {
                Attempt(
                    runId = it.run_id,
                    node = NodeId(it.node_id),
                    attempt = it.attempt.toInt(),
                    status = NodeStatus.valueOf(it.status),
                    version = it.version,
                    startedAt = it.started_at,
                    finishedAt = it.finished_at,
                    invokedBy = it.invoked_by,
                    correlationId = it.correlation_id,
                    inputs = it.inputs,
                    outputs = it.outputs,
                )
            }

    override suspend fun status(runId: String): RunStatus = foldStatus(attempts(runId))
}
