package com.personalos.app.data.work

import android.util.Log
import com.personalos.app.core.tag.FamilyState
import com.personalos.app.core.tag.RetagRun
import com.personalos.app.core.tag.SchedulingPolicy

/**
 * Runs bounded re-tag work, choosing **which family each chunk goes to** by
 * [policy] — weighted fairness — instead of whoever grabs the model lock.
 *
 * One [tick] runs up to [budget] chunks, so it fits an OS scheduling window, and
 * persists deficits so the fairness carries across runs. Exhaustion is discovered
 * by running: a finished family answers with a cheap empty query and is skipped
 * for the rest of the tick.
 */
class RetagDispatcher(
    private val families: List<FamilyState>,
    private val policy: SchedulingPolicy,
    private val store: ScheduleStore,
    private val runFamily: suspend (String) -> RetagRun,
    private val budget: Int = DEFAULT_BUDGET,
) {
    /** Runs up to [budget] chunks. Returns true while some family is not exhausted. */
    suspend fun tick(): Boolean {
        val deficits = store.load()
        var states = families.map { it.copy(deficit = deficits[it.family] ?: it.deficit) }

        var spent = 0
        while (spent < budget) {
            states = policy.credit(states)
            var ranThisRound = false
            while (spent < budget) {
                val family = policy.pick(states) ?: break
                val run = runCatching { runFamily(family) }.getOrElse { RetagRun(0, exhausted = false) }
                states = states.map { if (it.family == family) it.copy(exhausted = run.exhausted) else it }
                states = policy.charge(states, family)
                spent++
                ranThisRound = true
                if (run.tagged > 0) Log.i(TAG, "retag $family: +${run.tagged}")
            }
            // A whole round that ran nothing means every family is exhausted.
            if (!ranThisRound) break
        }

        store.save(states.associate { it.family to it.deficit })
        return states.any { !it.exhausted }
    }

    companion object {
        const val TAG = "RetagDispatch"

        /** Chunks per tick. Six families × ~1 round fits a background window. */
        const val DEFAULT_BUDGET = 12
    }
}
