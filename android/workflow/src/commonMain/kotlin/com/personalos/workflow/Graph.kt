package com.personalos.workflow

/** What a node *is*. The whole control vocabulary is [FORK], [JOIN] and [BRANCH]. */
enum class NodeKind { TASK, FORK, JOIN, BRANCH }

/** The two outgoing edges of a [NodeKind.BRANCH]. */
enum class Branch { THEN, ELSE }

/**
 * One step in the graph. A `TASK` carries the id of a registered
 * [WorkflowStep]; a `BRANCH` carries the id of a registered [WorkflowPredicate].
 * `FORK` and `JOIN` are structural and carry neither.
 */
data class Node(
    val id: NodeId,
    val kind: NodeKind,
    val step: String? = null,
    val predicate: String? = null,
    val attempts: Int = 1,
) {
    init {
        require(kind != NodeKind.TASK || step != null) { "a TASK node needs a step id: $id" }
        require(kind != NodeKind.BRANCH || predicate != null) { "a BRANCH node needs a predicate id: $id" }
        require(attempts >= 1) { "attempts must be at least 1: $id" }
    }
}

/** A directed edge. [branch] is set only on the two edges out of a `BRANCH`. */
data class Edge(
    val from: NodeId,
    val to: NodeId,
    val branch: Branch? = null,
)

class WorkflowGraphException(
    message: String,
) : IllegalArgumentException(message)

/**
 * An immutable directed graph — the NetworkX-shaped surface the engine needs and
 * nothing more: successors/predecessors, a topological order (Kahn's algorithm),
 * levelisation, and validation. Built with [WorkflowBuilder], never by hand.
 */
class WorkflowGraph internal constructor(
    val nodes: Map<NodeId, Node>,
    val edges: List<Edge>,
) {
    private val outgoing: Map<NodeId, List<Edge>> = edges.groupBy { it.from }
    private val incoming: Map<NodeId, List<Edge>> = edges.groupBy { it.to }

    fun node(id: NodeId): Node = nodes[id] ?: throw WorkflowGraphException("unknown node: $id")

    /** Every node with no predecessor: a run starts at all of them, in parallel. */
    fun roots(): List<NodeId> = nodes.keys.filter { predecessors(it).isEmpty() }

    fun successors(id: NodeId): List<NodeId> = outgoing[id].orEmpty().map { it.to }

    fun predecessors(id: NodeId): List<NodeId> = incoming[id].orEmpty().map { it.from }

    fun successors(
        id: NodeId,
        branch: Branch,
    ): List<NodeId> = outgoing[id].orEmpty().filter { it.branch == branch }.map { it.to }

    /** Which branch of [from] the edge to [to] leaves by, or null if it is unconditional. */
    fun edgeBranch(
        from: NodeId,
        to: NodeId,
    ): Branch? = outgoing[from].orEmpty().firstOrNull { it.to == to }?.branch

    /**
     * Kahn's algorithm. This is the dependency resolver *and* the cycle check: a
     * graph that does not fully drain is cyclic.
     */
    fun topologicalOrder(): List<NodeId> {
        val remaining = nodes.keys.associateWith { predecessors(it).size }.toMutableMap()
        val queue = ArrayDeque(remaining.filterValues { it == 0 }.keys)
        val order = ArrayList<NodeId>(nodes.size)
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            order += id
            for (next in successors(id)) {
                val left = remaining.getValue(next) - 1
                remaining[next] = left
                if (left == 0) queue += next
            }
        }
        if (order.size != nodes.size) throw WorkflowGraphException("the graph has a cycle")
        return order
    }

    /**
     * The longest-path depth of each node. Nodes on the same level are independent,
     * so they may run in parallel; every predecessor of a node is on a lower level,
     * so a single level-ordered pass sees every dependency already decided.
     */
    fun levels(): Map<NodeId, Int> {
        val level = HashMap<NodeId, Int>(nodes.size)
        for (id in topologicalOrder()) {
            level[id] = predecessors(id).maxOfOrNull { (level[it] ?: 0) + 1 } ?: 0
        }
        return level
    }

    /** Rejects a malformed graph at construction, never at run time. */
    fun validate(): WorkflowGraph {
        for ((id, node) in nodes) {
            when (node.kind) {
                NodeKind.FORK -> require(successors(id).size >= 2) { "FORK $id needs at least two branches" }
                NodeKind.JOIN -> require(predecessors(id).size >= 2) { "JOIN $id needs at least two branches" }
                NodeKind.BRANCH ->
                    require(successors(id, Branch.THEN).size == 1 && successors(id, Branch.ELSE).size == 1) {
                        "BRANCH $id needs exactly one THEN and one ELSE edge"
                    }
                NodeKind.TASK -> Unit
            }
        }
        topologicalOrder()
        return this
    }
}

/**
 * Builds a [WorkflowGraph]. The graph is data: adding a stage is adding a node and
 * an edge, never editing the engine (Open/Closed).
 */
class WorkflowBuilder {
    private val nodes = LinkedHashMap<NodeId, Node>()
    private val edges = ArrayList<Edge>()

    fun task(
        id: String,
        step: String,
        attempts: Int = 1,
    ): NodeId = add(Node(NodeId(id), NodeKind.TASK, step = step, attempts = attempts))

    fun fork(id: String): NodeId = add(Node(NodeId(id), NodeKind.FORK))

    fun join(id: String): NodeId = add(Node(NodeId(id), NodeKind.JOIN))

    fun branch(
        id: String,
        predicate: String,
    ): NodeId = add(Node(NodeId(id), NodeKind.BRANCH, predicate = predicate))

    fun add(node: Node): NodeId {
        check(!nodes.containsKey(node.id)) { "duplicate node id: ${node.id}" }
        nodes[node.id] = node
        return node.id
    }

    fun edge(
        from: NodeId,
        to: NodeId,
        branch: Branch? = null,
    ): WorkflowBuilder {
        edges += Edge(from, to, branch)
        return this
    }

    fun build(): WorkflowGraph = WorkflowGraph(nodes.toMap(), edges.toList()).validate()
}

/** A named workflow: an id plus its graph. */
data class Workflow(
    val id: String,
    val graph: WorkflowGraph,
)

/** The terse constructor: `workflow("ingest") { task("enrich", "enrich") }`. */
fun workflow(
    id: String,
    block: WorkflowBuilder.() -> Unit,
): Workflow = Workflow(id, WorkflowBuilder().apply(block).build())
