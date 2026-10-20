package com.personalos.app.data.work

import com.personalos.app.data.workflow.Enrichment

/**
 * Enrichment as a [DrainTask]. It runs one bounded batch through the bound
 * [Enrichment] path (workflow or direct) and discovers "more" from the same
 * candidate count Watchers sees.
 *
 * Enrichment writes content + `enriched_at` only (ADR 0010 §6); it never calls
 * retag or writes a vector.
 */
class EnrichmentDrainTask(
    private val enrichment: Enrichment,
    private val backlogCount: suspend () -> Int,
) : DrainTask {
    override suspend fun step(): DrainOutcome {
        val processed = enrichment.run { _, _ -> }
        return DrainOutcome(processed = processed, more = backlogCount() > 0)
    }

    override suspend fun backlog(): Int = backlogCount()
}

/**
 * Retagging as a [DrainTask]: promote the active tagger, run one weighted
 * dispatcher tick, and report its take plus whether any family still has work.
 *
 * [prepare] is where the model upgrade and active-tagger promotion happen, so
 * the backlog query and the writes always agree on which tagger is active.
 */
class RetagDrainTask(
    private val prepare: suspend () -> Unit,
    private val dispatcher: RetagDispatcher,
    private val backlogCount: suspend () -> Int,
) : DrainTask {
    override suspend fun step(): DrainOutcome {
        prepare()
        val tick = dispatcher.tickOutcome()
        return DrainOutcome(processed = tick.tagged, more = tick.more)
    }

    override suspend fun backlog(): Int = backlogCount()
}
