package com.personalos.workflow

/** A run: which workflow, under which run id. */
data class WorkflowRun(
    val workflowId: String,
    val runId: String,
)

/**
 * How a run is *hosted* on a platform — the one platform-varying piece (ADR 0009 §5).
 * The engine, graph and status model are identical everywhere; only this differs:
 *
 *  - Android: an implementation that submits the run to WorkManager, so it survives
 *    process death, Doze and reboot.
 *  - JVM / iOS / native: an implementation that drives the engine in-process.
 *
 * iOS needs no code of its own until it wants a durable host; until then it uses the
 * in-process backend unchanged.
 */
interface WorkflowBackend {
    suspend fun submit(run: WorkflowRun)
}
