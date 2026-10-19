package com.personalos.workflow

/** One run's own row: identity and **ownership**, never status (ADR 0010 §3). */
data class RunState(
    val runId: String,
    val workflowId: String,
    val definitionVersion: Int,
    val owner: String?,
    val leaseExpiresAt: Long?,
    val startedAt: Long,
)

/**
 * One node execution attempt. Written **before** the step runs (ADR 0010 I1), and
 * finished with an outcome guarded by [version] (I3 + OCC). [inputs]/[outputs] are
 * references — a cursor, a count, a range — never payloads.
 */
data class Attempt(
    val runId: String,
    val node: NodeId,
    val attempt: Int,
    val status: NodeStatus,
    val version: Long = 0,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val invokedBy: String = "",
    val correlationId: String = "",
    val inputs: String? = null,
    val outputs: String? = null,
)

/**
 * The run ledger: write-ahead intent per attempt, plus a lease for ownership.
 *
 * Deliberately separate from [RunStore], which is the engine's **resume** port
 * (terminal node state, and nothing else). This is the **record** port: what was
 * attempted, by whom, when. A host may supply both over the same database.
 */
interface RunLedger {
    /**
     * Create the run, or take over one whose lease is free/expired. True iff this
     * caller now owns it — so two hosts cannot drive one run.
     */
    suspend fun claim(
        runId: String,
        workflowId: String,
        definitionVersion: Int,
        owner: String,
        now: Long,
        leaseMillis: Long,
    ): Boolean

    suspend fun release(
        runId: String,
        owner: String,
        now: Long,
    ): Boolean

    suspend fun run(runId: String): RunState?

    /**
     * The next attempt number for a node: `max(attempt) + 1`, or 1. Read this before
     * [beginAttempt] so numbering continues across a resume instead of restarting at
     * 1 and collapsing the retry history.
     */
    suspend fun nextAttempt(
        runId: String,
        node: NodeId,
    ): Int

    /**
     * Write intent. **Not** idempotent by constraint: the attempt number comes from
     * [nextAttempt] and the run is leased, so a duplicate is a bug and throws rather
     * than being swallowed. Idempotency is decided by *reading* [attempts] and
     * skipping terminal work before this is ever called.
     */
    suspend fun beginAttempt(attempt: Attempt)

    /** OCC: apply the outcome only if the stored version still equals [expectedVersion]. */
    suspend fun finishAttempt(
        runId: String,
        node: NodeId,
        attempt: Int,
        expectedVersion: Long,
        status: NodeStatus,
        outputs: String?,
        now: Long,
    ): Boolean

    suspend fun attempts(runId: String): List<Attempt>

    /**
     * Derived from the attempts — never stored (ADR 0010 §3). Folded over the
     * **latest attempt per node**, so a superseded attempt (one the host died on,
     * or a transient failure that was retried) cannot pin or fail the run.
     */
    suspend fun status(runId: String): RunStatus
}

/**
 * The one status rule, shared by every ledger: the latest attempt per node decides.
 * A superseded attempt is history, not state.
 */
internal fun foldStatus(attempts: List<Attempt>): RunStatus {
    val latest =
        attempts
            .groupBy { it.node }
            .mapNotNull { (_, tries) -> tries.maxByOrNull { it.attempt } }
    if (latest.isEmpty()) return RunStatus.RUNNING
    if (latest.any { it.status == NodeStatus.FAILED || it.status == NodeStatus.RETRY_EXHAUSTED }) return RunStatus.FAILED
    if (latest.any { it.status == NodeStatus.PAUSED }) return RunStatus.PAUSED
    return if (latest.all { Statuses.isTerminal(it.status) }) RunStatus.SUCCEEDED else RunStatus.RUNNING
}

/** In-process [RunLedger]; the contract oracle the durable one must match. */
class InMemoryRunLedger : RunLedger {
    private val runs = LinkedHashMap<String, RunState>()
    private val attempts = LinkedHashMap<String, LinkedHashMap<Pair<NodeId, Int>, Attempt>>()

    override suspend fun claim(
        runId: String,
        workflowId: String,
        definitionVersion: Int,
        owner: String,
        now: Long,
        leaseMillis: Long,
    ): Boolean {
        val existing = runs[runId]
        if (existing == null) {
            runs[runId] =
                RunState(
                    runId = runId,
                    workflowId = workflowId,
                    definitionVersion = definitionVersion,
                    owner = owner,
                    leaseExpiresAt = now + leaseMillis,
                    startedAt = now,
                )
            return true
        }
        val free = existing.owner == null || (existing.leaseExpiresAt ?: 0L) < now
        if (!free) return false
        runs[runId] = existing.copy(owner = owner, leaseExpiresAt = now + leaseMillis)
        return true
    }

    override suspend fun release(
        runId: String,
        owner: String,
        now: Long,
    ): Boolean {
        val existing = runs[runId] ?: return false
        if (existing.owner != owner) return false
        runs[runId] = existing.copy(owner = null, leaseExpiresAt = null)
        return true
    }

    override suspend fun run(runId: String): RunState? = runs[runId]

    override suspend fun nextAttempt(
        runId: String,
        node: NodeId,
    ): Int = (attempts[runId]?.entries?.filter { it.key.first == node }?.maxOfOrNull { it.key.second } ?: 0) + 1

    override suspend fun beginAttempt(attempt: Attempt) {
        val byNode = attempts.getOrPut(attempt.runId) { LinkedHashMap() }
        val key = attempt.node to attempt.attempt
        check(!byNode.containsKey(key)) { "an attempt with that number already exists: $key" }
        byNode[key] = attempt.copy(version = 1)
    }

    override suspend fun finishAttempt(
        runId: String,
        node: NodeId,
        attempt: Int,
        expectedVersion: Long,
        status: NodeStatus,
        outputs: String?,
        now: Long,
    ): Boolean {
        val byNode = attempts[runId] ?: return false
        val key = node to attempt
        val existing = byNode[key] ?: return false
        if (existing.version != expectedVersion) return false
        byNode[key] = existing.copy(status = status, outputs = outputs, finishedAt = now, version = existing.version + 1)
        return true
    }

    override suspend fun attempts(runId: String): List<Attempt> =
        attempts[runId]
            ?.values
            ?.sortedWith(compareBy({ it.node.value }, { it.attempt }))
            .orEmpty()

    override suspend fun status(runId: String): RunStatus = foldStatus(attempts(runId))
}
