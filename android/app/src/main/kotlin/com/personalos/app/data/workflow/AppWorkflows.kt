package com.personalos.app.data.workflow

import com.personalos.workflow.Workflow
import com.personalos.workflow.workflow

/**
 * The app's workflows, as **data**. A new stage is a node + an edge here; the
 * engine, the worker and the scheduler do not change (ADR 0009).
 *
 * Each drain stage is its own one-node workflow (ADR 0010): enrichment and
 * retagging converge through the store. There is deliberately no edge between
 * them — an enriched item is discovered by retag's backlog query, not by a
 * workflow call.
 */
object AppWorkflows {
    /** Registered step ids; the definition and the step map must agree. */
    const val ENRICH = "enrich"
    const val RETAG = "retag"

    /** Node ids inside the workflow. A step *result* is keyed by node, not step id. */
    const val ENRICH_NODE = "enrich"
    const val RETAG_NODE = "retag"

    /** One bounded enrichment batch, recorded as its own run. */
    fun enrich(): Workflow = workflow(ENRICH) { task(ENRICH_NODE, ENRICH) }

    /** One bounded re-tag pass, recorded as its own run. */
    fun retag(): Workflow = workflow(RETAG) { task(RETAG_NODE, RETAG) }
}
