package com.personalos.app.core.tag

/**
 * One family's scheduling state.
 *
 *  - [weight] — priority: credit earned per round (chunks it may run).
 *  - [deficit] — fairness: credit not yet spent; a family that just ran goes negative
 *    and therefore yields.
 *  - [exhausted] — the walk reached the end of the family; it earns and runs nothing.
 */
data class FamilyState(
    val family: String,
    val weight: Int,
    val deficit: Int = 0,
    val exhausted: Boolean = false,
)

/**
 * Which source family re-tags next. **Pure**, so the policy is tested without a
 * device, and a different policy (strict priority, earliest-deadline) is a new
 * implementation — never an edit to the dispatcher (Open/Closed).
 */
interface SchedulingPolicy {
    /** Start a round: every non-exhausted family earns [FamilyState.weight] credit. */
    fun credit(states: List<FamilyState>): List<FamilyState>

    /** The runnable family with the highest deficit, or null when none can run. */
    fun pick(states: List<FamilyState>): String?

    /** Spend one unit of [family]'s credit. */
    fun charge(
        states: List<FamilyState>,
        family: String,
    ): List<FamilyState>
}

/**
 * **Deficit round-robin**: weights give priority, deficits give fairness.
 *
 * Over one round a weight-4 family runs up to 4 chunks while a weight-1 family
 * runs 1 — but a family that just ran goes into deficit and yields, so a
 * low-weight family still runs every round and nothing starves.
 */
class DeficitRoundRobin : SchedulingPolicy {
    override fun credit(states: List<FamilyState>): List<FamilyState> = states.map { if (it.exhausted) it else it.copy(deficit = it.deficit + it.weight) }

    override fun pick(states: List<FamilyState>): String? =
        states
            .filter { !it.exhausted && it.deficit >= COST }
            .maxWithOrNull(compareBy({ it.deficit }, { it.weight }, { it.family }))
            ?.family

    override fun charge(
        states: List<FamilyState>,
        family: String,
    ): List<FamilyState> = states.map { if (it.family == family) it.copy(deficit = it.deficit - COST) else it }

    private companion object {
        /** One chunk costs one unit; a weight is directly "chunks per round". */
        const val COST = 1
    }
}
