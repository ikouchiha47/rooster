package com.personalos.workflow

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** The outcome of a whole run. */
enum class RunStatus { SUCCEEDED, FAILED, PAUSED }

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
) {
    suspend fun run(
        workflow: Workflow,
        runId: String,
        onProgress: suspend (node: NodeId, done: Int, total: Int) -> Unit = { _, _, _ -> },
    ): RunResult {
        val graph = workflow.graph
        val order = graph.topologicalOrder()
        val levels = graph.levels()

        val status = LinkedHashMap<NodeId, NodeStatus>()
        val taken = HashMap<NodeId, Branch>()
        for (id in order) {
            val saved = store.get(runId, id) ?: continue
            if (Statuses.isTerminal(saved.status)) {
                status[id] = saved.status
                saved.branch?.let { taken[id] = it }
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
                store.put(runId, outcome.id, NodeState(outcome.status, outcome.branch))
            }
        }

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
            if (upstreamFailed || !live) return NodeOutcome(id, NodeStatus.SKIPPED, null)
        }

        return when (node.kind) {
            NodeKind.FORK, NodeKind.JOIN -> NodeOutcome(id, NodeStatus.SUCCEEDED, null)
            NodeKind.BRANCH -> {
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
        var attempt = 1
        while (true) {
            when (val outcome = step.run(ctx)) {
                is StepOutcome.Done -> return NodeOutcome(id, NodeStatus.SUCCEEDED, null, outcome.result)
                StepOutcome.Skipped -> return NodeOutcome(id, NodeStatus.SKIPPED, null)
                StepOutcome.Paused -> return NodeOutcome(id, NodeStatus.PAUSED, null)
                is StepOutcome.Failed ->
                    when {
                        outcome.failure == Failure.PERMANENT -> return NodeOutcome(id, NodeStatus.FAILED, null)
                        attempt >= node.attempts -> return NodeOutcome(id, NodeStatus.RETRY_EXHAUSTED, null)
                        else -> attempt++
                    }
            }
        }
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

    private data class NodeOutcome(
        val id: NodeId,
        val status: NodeStatus,
        val branch: Branch?,
        val result: String? = null,
    )
}
