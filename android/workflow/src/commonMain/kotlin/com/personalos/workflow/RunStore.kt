package com.personalos.workflow

/** One node's recorded result: its [status], and for a `BRANCH`, the edge it took. */
data class NodeState(
    val status: NodeStatus,
    val branch: Branch? = null,
)

/**
 * Where a run's per-node state lives — the port that makes a run resumable. The
 * engine depends only on this; a host supplies an implementation (Room on Android,
 * in-memory in tests, anything elsewhere). Separate from [WorkflowBackend], which
 * decides *how a run is hosted*, not *what has completed*.
 */
interface RunStore {
    suspend fun get(
        runId: String,
        node: NodeId,
    ): NodeState?

    suspend fun put(
        runId: String,
        node: NodeId,
        state: NodeState,
    )

    suspend fun all(runId: String): Map<NodeId, NodeState>
}

/** The simplest [RunStore]; enough for tests and single-process use. */
class InMemoryRunStore : RunStore {
    private val runs = LinkedHashMap<String, LinkedHashMap<NodeId, NodeState>>()

    override suspend fun get(
        runId: String,
        node: NodeId,
    ): NodeState? = runs[runId]?.get(node)

    override suspend fun put(
        runId: String,
        node: NodeId,
        state: NodeState,
    ) {
        runs.getOrPut(runId) { LinkedHashMap() }[node] = state
    }

    override suspend fun all(runId: String): Map<NodeId, NodeState> = runs[runId]?.toMap().orEmpty()
}
