package com.personalos.app.data

import kotlinx.coroutines.flow.Flow

/**
 * Single owner of the rules fact (ADR 0003).
 *
 * Storage only in this slice: read and write [RuleEntity] rows. Evaluation,
 * condition parsing and validation are deliberately absent — the predicate
 * language they need does not exist yet, and inventing a condition schema here
 * would pre-empt it.
 */
class RuleRepository(
    private val dao: RuleDao,
) {
    fun observe(): Flow<List<RuleEntity>> = dao.observeAll()

    suspend fun all(): List<RuleEntity> = dao.all()

    suspend fun insertAll(rules: List<RuleEntity>) {
        dao.insertAll(rules)
    }
}
