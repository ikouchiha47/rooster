package com.personalos.app.data.workflow

import com.personalos.workflow.Workflow
import com.personalos.workflow.workflow

/**
 * The app's workflows, as **data**. A new stage is a node + an edge here; the
 * engine, the worker and the scheduler do not change (ADR 0009).
 */
object AppWorkflows {
    /** Registered step ids; the definition and the step map must agree. */
    const val ENRICH = "enrich"
    const val RETAG = "retag"

    /** Node ids inside the workflow. A step *result* is keyed by node, not step id. */
    const val ENRICH_NODE = "enrich"
    const val RETAG_NODE = "retag"

    /** The workflow's id (a run is `(workflowId, runId)`). */
    const val ENRICH_RETAG = "enrich-retag"

    /**
     * Stage 1 enriches a batch; stage 2 re-tags what the store now has.
     *
     * Both stages are store-driven, so the edge is ordering, not payload: the
     * retag reads the same events table the enrich just wrote. A transient
     * failure is retried once by the engine; a permanent one fails the run.
     */
    fun enrichRetag(): Workflow =
        workflow(ENRICH_RETAG) {
            val enrich = task(ENRICH_NODE, ENRICH, attempts = 2)
            val retag = task(RETAG_NODE, RETAG)
            edge(enrich, retag)
        }
}
