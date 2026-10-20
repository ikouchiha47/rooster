package com.personalos.workflow

/** A permanent failure fails the node; a transient one is retried, then exhausted. */
enum class Failure { TRANSIENT, PERMANENT }

/**
 * A step's outcome — a closed set, so no node ends in an ambiguous state.
 * [Paused] means "not now" (backpressure), not "failed": the run pauses resumably.
 */
sealed interface StepOutcome {
    /**
     * The step succeeded. [result] is a small, optional payload for the *caller*
     * (a count, a summary) — not a channel between steps: steps share the store,
     * never the run object.
     */
    data class Done(
        val result: String? = null,
    ) : StepOutcome

    data object Skipped : StepOutcome

    data object Paused : StepOutcome

    data class Failed(
        val failure: Failure,
        /** Why, in the step's words — persisted so a reader can see the error. */
        val reason: String? = null,
    ) : StepOutcome
}

/** Everything a step or predicate is told about the invocation. */
data class StepContext(
    val runId: String,
    val node: NodeId,
    /** The step's own progress within its node; a no-op when nobody is watching. */
    val progress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
)

/** One unit of work, registered under an id and referenced by a `TASK` node. */
fun interface WorkflowStep {
    suspend fun run(ctx: StepContext): StepOutcome
}

/** Decides which edge of a `BRANCH` node is taken. */
fun interface WorkflowPredicate {
    suspend fun test(ctx: StepContext): Boolean
}
