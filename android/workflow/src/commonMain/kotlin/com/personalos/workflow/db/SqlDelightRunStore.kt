package com.personalos.workflow.db

import com.personalos.workflow.Branch
import com.personalos.workflow.NodeId
import com.personalos.workflow.NodeState
import com.personalos.workflow.NodeStatus
import com.personalos.workflow.RunStore

/**
 * The durable [RunStore]: per-run, per-node state in SQLite, so a run survives the
 * process being killed and resumes instead of restarting. SQLDelight is one
 * implementation of the port — a host may pass its own (e.g. one over the app's Room
 * database) instead; the engine does not know the difference (ADR 0009 §6).
 *
 * A status is stored by [NodeStatus.name] and a branch by [Branch.name], so the
 * columns stay readable and a new status is a Kotlin change, not a schema change.
 */
class SqlDelightRunStore(
    private val database: WorkflowDatabase,
) : RunStore {
    override suspend fun get(
        runId: String,
        node: NodeId,
    ): NodeState? {
        val row = database.runNodeQueries.selectForNode(runId, node.value).executeAsOneOrNull() ?: return null
        return NodeState(NodeStatus.valueOf(row.status), row.branch?.let { Branch.valueOf(it) })
    }

    override suspend fun put(
        runId: String,
        node: NodeId,
        state: NodeState,
    ) {
        database.runNodeQueries.upsert(runId, node.value, state.status.name, state.branch?.name)
    }

    override suspend fun all(runId: String): Map<NodeId, NodeState> =
        database.runNodeQueries
            .selectForRun(runId)
            .executeAsList()
            .associate {
                NodeId(it.node_id) to NodeState(NodeStatus.valueOf(it.status), it.branch?.let(Branch::valueOf))
            }
}
