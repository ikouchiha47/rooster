package com.personalos.app.data

/**
 * Asks for a rule to be backfilled over recent history.
 *
 * A one-method interface so [RuleRepository] can enqueue a backfill when a rule
 * is created or edited without depending on WorkManager — the tests pass a
 * recorder, and production passes one that schedules the worker. The default in
 * [RuleRepository] does nothing, so a caller that does not care (or a test) need
 * not supply one.
 *
 * [generation] identifies *this* request: a later edit uses a new one, and a
 * retry of the same work uses the same one, so a run resumes rather than
 * restarts.
 */
fun interface RuleBackfillScheduler {
    fun enqueue(
        ruleId: String,
        generation: Long,
    )
}
