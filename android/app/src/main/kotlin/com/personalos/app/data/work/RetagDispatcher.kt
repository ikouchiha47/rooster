package com.personalos.app.data.work

import android.util.Log
import com.personalos.app.core.resilience.CircuitStore
import com.personalos.app.core.resilience.InMemoryCircuitStore
import com.personalos.app.core.tag.FamilyState
import com.personalos.app.core.tag.RetagRun
import com.personalos.app.core.tag.SchedulingPolicy
import com.personalos.app.core.work.RetryPolicy
import com.personalos.app.core.work.classifyFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Runs bounded re-tag work, choosing **which family each chunk goes to** by
 * [policy] — weighted fairness — instead of whoever grabs the model lock.
 *
 * One [tick] runs up to [budget] chunks, so it fits an OS scheduling window, and
 * persists deficits so the fairness carries across runs. Exhaustion is discovered
 * by running: a finished family answers with a cheap empty query and is skipped.
 *
 * Failures are **bounded, not endless**: a transient error is retried with backoff
 * up to [retry], a permanent one is not, and a family that keeps failing is paused
 * by [health] (circuit open). Crucially a failure is reported as *exhausted for this
 * tick* — **not "more work"** — or the caller would loop forever.
 */
class RetagDispatcher(
    private val families: List<FamilyState>,
    private val policy: SchedulingPolicy,
    private val store: ScheduleStore,
    private val runFamily: suspend (String) -> RetagRun,
    private val budget: Int = DEFAULT_BUDGET,
    private val retry: RetryPolicy = RetryPolicy(),
    private val health: CircuitStore = InMemoryCircuitStore(),
    private val now: () -> Long = System::currentTimeMillis,
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
                // A paused family (circuit open) is skipped at no budget cost, and is
                // not "more work" — so a broken family cannot keep the run alive.
                if (health.get(family).paused(now())) {
                    states = states.map { if (it.family == family) it.copy(exhausted = true) else it }
                    continue
                }
                val run = attempt(family)
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

    /**
     * Runs one family, retrying a **transient** failure with backoff. A failure that
     * outlives [retry] — or a permanent one — is reported as exhausted for this tick
     * (so the caller stops), and health is recorded so the circuit can open.
     */
    private suspend fun attempt(family: String): RetagRun {
        var attempt = 0
        while (true) {
            try {
                val run = runFamily(family)
                health.onSuccess(family)
                return run
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val kind = classifyFailure(e)
                val after = health.onFailure(family, kind, now())
                val delayMs = retry.delayFor(attempt, kind)
                Log.w(TAG, "retag $family failed ($kind) attempt=$attempt paused=${after.paused(now())}", e)
                if (delayMs == null || after.paused(now())) {
                    // Give up this tick. The cursor is unchanged, so a later run retries.
                    return RetagRun(tagged = 0, exhausted = true)
                }
                delay(delayMs)
                attempt++
            }
        }
    }

    companion object {
        const val TAG = "RetagDispatch"

        /** Chunks per tick. Six families × ~1 round fits a background window. */
        const val DEFAULT_BUDGET = 12
    }
}
