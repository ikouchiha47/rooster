package com.personalos.app.data

import com.personalos.app.core.rules.ActionJson
import com.personalos.app.core.rules.ConditionJson
import com.personalos.app.core.tag.Ulid
import kotlinx.coroutines.flow.Flow

/**
 * Single owner of the rules fact (ADR 0003, CODE-DESIGN-GUIDELINES.md §1).
 *
 * The authoring screen observes and edits through here; [RuleWriter] reads the
 * same rows to evaluate them. Seeded rows (`seeded = 1`) are locked and
 * add-only — `update`/`setEnabled`/`delete` on one is rejected with an
 * exception, and the DAO's own `AND seeded = 0` guards hold even for a caller
 * that bypasses this class. Affected-row counting (rather than a read-then-write)
 * keeps the rejection race-free, exactly as [SourceRepository] does.
 *
 * **Validation is the language's, not the evaluator's.** [create] and [update]
 * parse both documents through [ConditionJson] / [ActionJson] before a row is
 * written, so a typo in a key or an unusable regex fails loudly here rather than
 * shipping a rule that silently never matches (P7). They do **not** call
 * `RuleEvaluator.requireItemEvaluable`: a series predicate (`crossing`, `delta`,
 * `min`/`max`) is valid language that the item evaluator refuses by design (ADR
 * §8), and a monitor is exactly the rule that stores one. That refusal belongs
 * to the dry run and the writer, which report it.
 *
 * `position` is materialised onto the row from the parsed action, so the column
 * and `action_json` cannot drift — the action is the owner, the column is the
 * ordering-for-surfacing view of it.
 */
class RuleRepository(
    private val dao: RuleDao,
) {
    /** One `Flow<List<RuleEntity>>`: the read surface every consumer observes. */
    fun observe(): Flow<List<RuleEntity>> = dao.observeAll()

    /** A one-shot read of every rule, newest state — for the dry run and tests. */
    suspend fun all(): List<RuleEntity> = dao.all()

    /**
     * Creates a user rule after validating its condition and action.
     *
     * @throws IllegalArgumentException when the name is blank or either document
     * is malformed, has an unknown key, or carries an invalid value.
     */
    suspend fun create(
        name: String,
        conditionJson: String,
        actionJson: String,
        now: Long = System.currentTimeMillis(),
    ): RuleEntity {
        require(name.isNotBlank()) { "rule name must not be blank" }
        ConditionJson.parse(conditionJson)
        val action = ActionJson.parse(actionJson)
        val row =
            RuleEntity(
                id = Ulid.next(),
                name = name.trim(),
                enabled = true,
                seeded = false,
                conditionJson = conditionJson,
                actionJson = actionJson,
                position = action.position,
                createdAt = now,
                updatedAt = now,
            )
        dao.insertAll(listOf(row))
        return row
    }

    /**
     * Edits a user rule's name, condition and action in place.
     *
     * @throws IllegalArgumentException when the name is blank or either document
     * is malformed, has an unknown key, or carries an invalid value.
     * @throws IllegalStateException when the row is a locked seed or unknown.
     */
    suspend fun update(
        id: String,
        name: String,
        conditionJson: String,
        actionJson: String,
        now: Long = System.currentTimeMillis(),
    ) {
        require(name.isNotBlank()) { "rule name must not be blank" }
        ConditionJson.parse(conditionJson)
        val action = ActionJson.parse(actionJson)
        val touched =
            dao.updateUserOnly(
                id = id,
                name = name.trim(),
                conditionJson = conditionJson,
                actionJson = actionJson,
                position = action.position,
                updatedAt = now,
            )
        if (touched == 0) {
            throw IllegalStateException("rule is locked or unknown: $id")
        }
    }

    /**
     * Enables or disables a user rule. A disabled rule keeps its matches and
     * stops gaining new ones (R6).
     *
     * @throws IllegalStateException when the row is a locked seed or unknown.
     */
    suspend fun setEnabled(
        id: String,
        enabled: Boolean,
        now: Long = System.currentTimeMillis(),
    ) {
        if (dao.updateEnabledUserOnly(id, enabled, now) == 0) {
            throw IllegalStateException("rule is locked or unknown: $id")
        }
    }

    /**
     * Deletes a user rule. Existing matches are left alone: `item_rules` is
     * append-only (R5–R6).
     *
     * @throws IllegalStateException when the row is a locked seed or unknown.
     */
    suspend fun delete(id: String) {
        if (dao.deleteUserOnly(id) == 0) {
            throw IllegalStateException("rule is locked or unknown: $id")
        }
    }
}
