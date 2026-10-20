package com.personalos.workflow

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** The outcome of a whole run. RUNNING is derived, never stored (ADR 0010 §3). */
enum class RunStatus { RUNNING, SUCCEEDED, FAILED, PAUSED }

/** A run's terminal (or paused) state, every node's status, and its `Done` payloads. */
data class RunResult(
    val runId: String,
    val status: RunStatus,
    val nodes: Map<NodeId, NodeStatus>,
    /** The optional `Done` payload each node produced, keyed by node. */
    val results: Map<NodeId, String> = emptyMap(),
)

/**
 * Walks a [Workflow] once and reports the result.
 *
 * Execution is **level-ordered**: [WorkflowGraph.levels] puts every predecessor of a
 * node on a strictly lower level, so a single pass sees each dependency already
 * decided, nodes on the same level are independent and run in parallel, and a run
 * is resumable by seeding [RunStore] with the nodes that already completed.
 *
 * A node runs only when it is *live*: reachable from a root through taken branches.
 * An untaken branch, or anything downstream of a failure, is `SKIPPED`, not `FAILED`
 * — so an if/else rejoins its `JOIN` cleanly instead of deadlocking on the branch it
 * did not take.
 */
class Engine(
    private val steps: Map<String, WorkflowStep>,
    private val predicates: Map<String, WorkflowPredicate> = emptyMap(),
    private val store: RunStore = InMemoryRunStore(),
    /**
     * The record port (ADR 0010). When supplied, the engine writes **intent before
     * work** and takes a lease; when absent, the run is a bare stage (Model 1) and
     * nothing is recorded. Optional by design: Model 1 is the n=1 case.
     */
    private val ledger: RunLedger? = null,
    /** The observability log. Never state: [Journal.Noop] is a valid host. */
    private val journal: Journal = Journal.Noop,
    private val owner: String = "local",
    private val invokedBy: String = "manual",
    private val now: () -> Long = { 0L },
) {
    suspend fun run(
        workflow: Workflow,
        runId: String,
        onProgress: suspend (node: NodeId, done: Int, total: Int) -> Unit = { _, _, _ -> },
    ): RunResult {
        // One host per run: a lost claim means someone else owns it, so derive the
        // state and never walk it (ADR 0010 §9).
        if (ledger != null && !ledger.claim(runId, workflow.id, workflow.version, owner, now(), LEASE_MILLIS)) {
            return RunResult(runId, ledger.status(runId), store.all(runId).mapValues { it.value.status }, emptyMap())
        }

        val graph = workflow.graph
        val order = graph.topologicalOrder()
        val levels = graph.levels()

        val status = LinkedHashMap<NodeId, NodeStatus>()
        val taken = HashMap<NodeId, Branch>()
        // Resume. With a ledger the ledger **is** the checkpoint, so a node's
        // outcome has exactly one writer (ADR 0010 §3) — never the store too.
        if (ledger != null) {
            ledger
                .attempts(runId)
                .groupBy { it.node }
                .forEach { (node, tries) ->
                    val latest = tries.maxByOrNull { it.attempt } ?: return@forEach
                    if (Statuses.isTerminal(latest.status)) status[node] = latest.status
                }
        } else {
            for (id in order) {
                val saved = store.get(runId, id) ?: continue
                if (Statuses.isTerminal(saved.status)) {
                    status[id] = saved.status
                    saved.branch?.let { taken[id] = it }
                }
            }
        }

        val results = LinkedHashMap<NodeId, String>()
        val lastLevel = levels.values.maxOrNull() ?: 0
        for (level in 0..lastLevel) {
            val batch = order.filter { levels.getValue(it) == level && status[it] == null }
            if (batch.isEmpty()) continue
            val resolved =
                coroutineScope {
                    batch.map { id -> async { execute(graph, id, runId, status, taken, onProgress) } }.awaitAll()
                }
            for (outcome in resolved) {
                status[outcome.id] = outcome.status
                outcome.branch?.let { taken[outcome.id] = it }
                outcome.result?.let { results[outcome.id] = it }
                if (ledger == null) store.put(runId, outcome.id, NodeState(outcome.status, outcome.branch))
            }
        }

        ledger?.release(runId, owner, now())
        return RunResult(runId, runStatus(status), status.toMap(), results.toMap())
    }

    private suspend fun execute(
        graph: WorkflowGraph,
        id: NodeId,
        runId: String,
        status: Map<NodeId, NodeStatus>,
        taken: Map<NodeId, Branch>,
        onProgress: suspend (node: NodeId, done: Int, total: Int) -> Unit,
    ): NodeOutcome {
        val node = graph.node(id)
        val predecessors = graph.predecessors(id)

        if (predecessors.isNotEmpty()) {
            val upstreamFailed =
                predecessors.any { p ->
                    val s = status[p]
                    s == NodeStatus.FAILED || s == NodeStatus.RETRY_EXHAUSTED
                }
            val live = predecessors.any { p -> isActive(graph, p, id, status, taken) }
            if (upstreamFailed || !live) {
                // Never live, so it never started: one END explains the skip
                // without pretending a START happened.
                note(
                    JournalEvent(
                        at = now(),
                        host = HOST,
                        runId = runId,
                        node = id.value,
                        attempt = 1,
                        phase = JournalPhase.END,
                        status = NodeStatus.SKIPPED,
                    ),
                )
                return NodeOutcome(id, NodeStatus.SKIPPED, null)
            }
        }

        return when (node.kind) {
            NodeKind.FORK, NodeKind.JOIN ->
                journaled(runId, id) { NodeOutcome(id, NodeStatus.SUCCEEDED, null) }

            NodeKind.BRANCH ->
                journaled(runId, id) {
                    val predicate =
                        predicates[node.predicate]
                            ?: throw WorkflowGraphException("no predicate registered: ${node.predicate}")
                    val branch = if (predicate.test(StepContext(runId, id))) Branch.THEN else Branch.ELSE
                    NodeOutcome(id, NodeStatus.SUCCEEDED, branch)
                }

            NodeKind.TASK -> runTask(id, node, runId, onProgress)
        }
    }

    private suspend fun runTask(
        id: NodeId,
        node: Node,
        runId: String,
        onProgress: suspend (node: NodeId, done: Int, total: Int) -> Unit,
    ): NodeOutcome {
        val step =
            steps[node.step]
                ?: throw WorkflowGraphException("no step registered: ${node.step}")
        // One context for every attempt, so a retry reports progress the same way.
        val ctx = StepContext(runId, id) { done, total -> onProgress(id, done, total) }
        // Continue the numbering: a resumed run must not reuse attempt 1.
        var attempt = ledger?.nextAttempt(runId, id) ?: 1
        while (true) {
            val startedAt = now()
            note(
                JournalEvent(
                    at = startedAt,
                    host = HOST,
                    runId = runId,
                    node = id.value,
                    attempt = attempt,
                    phase = JournalPhase.START,
                ),
            )
            // Write-ahead: the attempt exists before the step's side effect runs.
            ledger?.beginAttempt(
                Attempt(
                    runId = runId,
                    node = id,
                    attempt = attempt,
                    status = NodeStatus.RUNNING,
                    startedAt = now(),
                    invokedBy = invokedBy,
                    correlationId = runId,
                ),
            )
            when (val outcome = step.run(ctx)) {
                is StepOutcome.Done -> return finish(runId, id, attempt, startedAt, NodeStatus.SUCCEEDED, outcome.result)
                StepOutcome.Skipped -> return finish(runId, id, attempt, startedAt, NodeStatus.SKIPPED, null)
                StepOutcome.Paused -> return finish(runId, id, attempt, startedAt, NodeStatus.PAUSED, null)
                is StepOutcome.Failed ->
                    when {
                        outcome.failure == Failure.PERMANENT ->
                            return finish(runId, id, attempt, startedAt, NodeStatus.FAILED, outcome.reason)

                        attempt >= node.attempts ->
                            return finish(runId, id, attempt, startedAt, NodeStatus.RETRY_EXHAUSTED, outcome.reason)

                        else -> {
                            finish(runId, id, attempt, startedAt, NodeStatus.FAILED, outcome.reason)
                            attempt++
                        }
                    }
            }
        }
    }

    /** Record the outcome against the attempt, then hand the node's result back. */
    private suspend fun finish(
        runId: String,
        id: NodeId,
        attempt: Int,
        startedAt: Long,
        status: NodeStatus,
        result: String?,
    ): NodeOutcome {
        val at = now()
        note(
            JournalEvent(
                at = at,
                host = HOST,
                runId = runId,
                node = id.value,
                attempt = attempt,
                phase = JournalPhase.END,
                status = status,
                durationMs = (at - startedAt).coerceAtLeast(0L),
                // A success has no error: the step's result is a payload, not a
                // diagnosis, and putting it here would fill the log with false errors.
                error = result.takeIf { status == NodeStatus.FAILED || status == NodeStatus.RETRY_EXHAUSTED },
            ),
        )
        ledger?.finishAttempt(runId, id, attempt, expectedVersion = 1L, status = status, outputs = result, now = at)
        return NodeOutcome(id, status, null, result)
    }

    /**
     * Telemetry is observability, so it may never fail the work it observes: a
     * sink that throws (a full disk, a schema mismatch) is ignored, and only
     * cancellation is allowed through.
     */
    private suspend fun note(event: JournalEvent) {
        try {
            journal.record(event)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Deliberately swallowed: losing a line beats losing the run.
        }
    }

    /** Journalled control nodes: they run but leave no attempt row. */
    private suspend fun journaled(
        runId: String,
        id: NodeId,
        block: suspend () -> NodeOutcome,
    ): NodeOutcome {
        val startedAt = now()
        note(JournalEvent(startedAt, HOST, runId, id.value, 1, JournalPhase.START))
        val outcome = block()
        val at = now()
        journal.record(
            JournalEvent(
                at = at,
                host = HOST,
                runId = runId,
                node = id.value,
                attempt = 1,
                phase = JournalPhase.END,
                status = outcome.status,
                durationMs = (at - startedAt).coerceAtLeast(0L),
            ),
        )
        return outcome
    }

    /** An edge carries liveness only if its source succeeded along *that* edge. */
    private fun isActive(
        graph: WorkflowGraph,
        from: NodeId,
        to: NodeId,
        status: Map<NodeId, NodeStatus>,
        taken: Map<NodeId, Branch>,
    ): Boolean {
        if (status[from] != NodeStatus.SUCCEEDED) return false
        if (graph.node(from).kind == NodeKind.BRANCH) return graph.edgeBranch(from, to) == taken[from]
        return true
    }

    private fun runStatus(status: Map<NodeId, NodeStatus>): RunStatus =
        when {
            status.values.any { it == NodeStatus.FAILED || it == NodeStatus.RETRY_EXHAUSTED } -> RunStatus.FAILED
            status.values.any { it == NodeStatus.PAUSED } -> RunStatus.PAUSED
            else -> RunStatus.SUCCEEDED
        }

    private companion object {
        /** Long enough to outlast a step batch, short enough that a dead host frees the run. */
        const val LEASE_MILLIS = 10 * 60 * 1000L

        /** The host name this engine writes to the journal. */
        const val HOST = "workflow"
    }

    private data class NodeOutcome(
        val id: NodeId,
        val status: NodeStatus,
        val branch: Branch?,
        val result: String? = null,
    )
}
