package com.personalos.workflow

/** A permanent failure fails the node; a transient one is retried, then exhausted. */
enum class Failure { TRANSIENT, PERMANENT }

/**
 * A step's outcome — a closed set, so no node ends in an ambiguous state.
 * [Paused] means "not now" (backpressure), not "failed": the run pauses resumably.
 */
sealed interface StepOutcome {
    data object Done : StepOutcome

    data object Skipped : StepOutcome

    data object Paused : StepOutcome

    data class Failed(
        val failure: Failure,
    ) : StepOutcome
}

/** Everything a step or predicate is told about the invocation. */
data class StepContext(
    val runId: String,
    val node: NodeId,
)

/** One unit of work, registered under an id and referenced by a `TASK` node. */
fun interface WorkflowStep {
    suspend fun run(ctx: StepContext): StepOutcome
}

/** Decides which edge of a `BRANCH` node is taken. */
fun interface WorkflowPredicate {
    suspend fun test(ctx: StepContext): Boolean
}
