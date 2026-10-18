package com.personalos.workflow

/**
 * The state of one node within one run. A single value, so `when` over it is
 * exhaustive — "partially applied" and "unknown" are not representable (ADR 0008 §6).
 */
enum class NodeStatus { PENDING, RUNNING, SUCCEEDED, SKIPPED, RETRY_EXHAUSTED, FAILED, PAUSED }

/**
 * Statuses as a bitmask, for the questions that are about a *set*: "is this
 * terminal?", "is this a success?". A single status is always a [NodeStatus];
 * only membership tests use the mask, so an `Int` never leaks into the model.
 */
object Statuses {
    fun bit(status: NodeStatus): Int = 1 shl status.ordinal

    fun mask(statuses: Iterable<NodeStatus>): Int = statuses.fold(0) { acc, s -> acc or bit(s) }

    /** No further transition is expected without a new run. */
    val TERMINAL: Int =
        mask(listOf(NodeStatus.SUCCEEDED, NodeStatus.SKIPPED, NodeStatus.FAILED, NodeStatus.RETRY_EXHAUSTED, NodeStatus.PAUSED))

    val SUCCESS: Int = mask(listOf(NodeStatus.SUCCEEDED, NodeStatus.SKIPPED))

    fun contains(
        mask: Int,
        status: NodeStatus,
    ): Boolean = (mask and bit(status)) != 0

    fun isTerminal(status: NodeStatus): Boolean = contains(TERMINAL, status)
}
