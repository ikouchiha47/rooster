package com.personalos.app.data.workflow

import com.personalos.app.core.tag.Retagger
import com.personalos.app.data.remote.ArticleEnricher
import com.personalos.workflow.Failure
import com.personalos.workflow.StepContext
import com.personalos.workflow.StepOutcome
import com.personalos.workflow.WorkflowStep
import kotlinx.coroutines.CancellationException

/**
 * Stage 1: one bounded enrichment batch. The step owns no cursor — the existing
 * `ArticleEnricher` does, and the store's `enriched_at` is idempotent, so a
 * retry re-reads the same candidate set rather than double-applying.
 */
class EnrichStep(
    private val enricher: ArticleEnricher,
) : WorkflowStep {
    override suspend fun run(ctx: StepContext): StepOutcome =
        try {
            // The count rides back as the step result, so the caller keeps the
            // `+N summaries` it had before the workflow path existed.
            val enriched = enricher.enrichBatch(onProgress = { done, total -> ctx.progress(done, total) })
            StepOutcome.Done(enriched.toString())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            StepOutcome.Failed(Failure.TRANSIENT)
        }
}

/**
 * One bounded re-tag pass over the store's derived backlog. A successful batch
 * writes markers, so the same call is safe to repeat; no cursor lives here.
 */
class RetagStep(
    private val retagger: Retagger,
    /** Optional battery policy: pause instead of draining the phone. */
    private val powerGuard: com.personalos.app.core.metrics.PowerGuard? = null,
) : WorkflowStep {
    override suspend fun run(ctx: StepContext): StepOutcome {
        // A policy pause is not a failure: the run stops resumably and the next
        // attempt re-reads the same backlog.
        if (powerGuard?.shouldPause() == true) return StepOutcome.Paused
        return try {
            retagger.run()
            StepOutcome.Done()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            StepOutcome.Failed(Failure.TRANSIENT)
        }
    }
}
