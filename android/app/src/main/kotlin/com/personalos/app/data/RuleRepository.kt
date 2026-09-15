package com.personalos.app.data

import com.personalos.app.core.rules.RuleSpecs
import com.personalos.app.core.tag.Ulid
import kotlinx.coroutines.flow.Flow

/**
 * Single owner of the rules fact (ADR 0002, CODE-DESIGN-GUIDELINES.md §1).
 *
 * Everything rule-shaped flows through here: the seeder inserts, Settings will
 * write, the ingestor reads. Seeded rows (`seeded = 1`) are locked and
 * add-only — `setEnabled`/`delete` on one is rejected with an exception, and
 * the DAO's own `AND seeded = 0` guards hold even for a caller that bypasses
 * this class. Affected-row counting (rather than a read-then-write) keeps the
 * rejection race-free.
 *
 * v1 has no edit path: a user rule changes by delete + re-add. The full engine
 * (tile scope, delivery, position) arrives later without touching this class's
 * contract.
 */
class RuleRepository(
    private val dao: RuleDao,
) {
    /** One `Flow<List<Rule>>`: the read surface every consumer observes. */
    fun observe(): Flow<List<RuleEntity>> = dao.observeAll()

    /** Enabled rows of any kind — what the ingestor polls. */
    suspend fun enabledRules(): List<RuleEntity> = dao.enabled()

    /**
     * Enables or disables a user rule.
     *
     * @throws IllegalStateException when the row is a locked seed or unknown.
     */
    suspend fun setEnabled(
        id: String,
        enabled: Boolean,
        now: Long = System.currentTimeMillis(),
    ) {
        if (dao.updateEnabled(id, enabled, now) == 0) {
            throw IllegalStateException("rule is locked or unknown: $id")
        }
    }

    /**
     * Deletes a user rule.
     *
     * @throws IllegalStateException when the row is a locked seed or unknown.
     */
    suspend fun delete(id: String) {
        if (dao.deleteUserOnly(id) == 0) {
            throw IllegalStateException("rule is locked or unknown: $id")
        }
    }

    /**
     * Inserts a user rule after validating its spec.
     *
     * @throws IllegalArgumentException when the name is blank, the kind is
     * unknown, or the spec is invalid for its kind.
     */
    suspend fun addUserRule(
        name: String,
        kind: String,
        specJson: String,
        now: Long = System.currentTimeMillis(),
    ): RuleEntity {
        require(name.isNotBlank()) { "rule name must not be blank" }
        RuleSpecs.parse(kind, specJson)
        val row =
            RuleEntity(
                id = Ulid.next(),
                name = name.trim(),
                kind = kind,
                specJson = specJson,
                seeded = false,
                enabled = true,
                createdAt = now,
                updatedAt = now,
            )
        dao.insertAll(listOf(row))
        return row
    }
}
