package com.personalos.app.data.workflow

import com.personalos.app.data.remote.ArticleEnricher
import com.personalos.workflow.NodeId

/**
 * How the app enriches — a seam, so the trigger never picks a path itself.
 *
 * Two implementations, one bound at composition from the stored flag: the direct
 * enricher call, or the enrich→retag workflow. The caller reports progress and the
 * enriched count the same way either way, which is what makes the swap invisible
 * from outside (the engine's step result and progress sink are what carry them).
 */
fun interface Enrichment {
    suspend fun run(onProgress: suspend (done: Int, total: Int) -> Unit): Int
}

/** One enrichment batch, called directly. No run is recorded. */
class DirectEnrichment(
    private val enricher: ArticleEnricher,
) : Enrichment {
    override suspend fun run(onProgress: suspend (done: Int, total: Int) -> Unit): Int = enricher.enrichBatch(onProgress = onProgress)
}

/**
 * Enrichment as one tracked and resumable run.
 *
 * The run is the **enrich** workflow only (ADR 0010). It never invokes retag:
 * retagging is a separate drain and discovers enriched rows through the store.
 */
class WorkflowEnrichment(
    private val runs: WorkflowRuns,
) : Enrichment {
    override suspend fun run(onProgress: suspend (done: Int, total: Int) -> Unit): Int {
        val result =
            runs.run(
                id = AppWorkflows.ENRICH,
                runId = "enrich-${System.currentTimeMillis()}",
                onProgress = { node, done, total ->
                    if (node == NodeId(AppWorkflows.ENRICH_NODE)) onProgress(done, total)
                },
            )
        // A missing or unparseable result is "unknown", not a wrong number.
        return result.results[NodeId(AppWorkflows.ENRICH_NODE)]?.toIntOrNull() ?: 0
    }
}
