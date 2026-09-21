package com.personalos.app.data

import com.personalos.app.core.rules.ConditionJson
import com.personalos.app.core.rules.MatchedClause
import com.personalos.app.core.rules.RuleItem
import com.personalos.app.core.rules.matchedClauses

/**
 * One fire as the Watchers feed renders it: which rule, which item, and **what
 * matched** — the clause with the stored value, not just the rule's name.
 */
data class RuleFire(
    val ruleId: String,
    /** `events.ulid`. */
    val itemId: String,
    val matchedAt: Long,
    val title: String,
    /** `events.source` — the identity rules match on. */
    val source: String,
    /** When the item itself happened (publish time), as opposed to [matchedAt]. */
    val itemTimestamp: Long,
    /** The clauses that held, e.g. `temp_c 40.2 > 40`. */
    val clauses: List<MatchedClause>,
)

/** A rule and its most recent fires, newest first. */
data class RuleWithFires(
    val rule: RuleEntity,
    val fires: List<RuleFire>,
)

/**
 * Reads fired rule matches (ADR 0003: matches are written once at ingest and
 * read wherever a view asks for them).
 *
 * Facts come from [RuleWriter.itemsFor], the same reader ingest uses, so a
 * fire's clause evaluation cannot disagree with the evaluation that produced
 * the fire. One batched read per table for the whole page — never one per row.
 *
 * Unreadable rows are skipped rather than thrown: a rule with malformed
 * condition JSON does not hide the other rules' fires.
 */
class RuleFireRepository(
    private val ruleDao: RuleDao,
    private val ruleFireDao: RuleFireDao,
    private val ruleWriter: RuleWriter,
) {
    /**
     * Every rule with its latest [perRule] fires — the Watchers body.
     *
     * A rule with no fires is still returned (with an empty list): the screen
     * shows the rule and says it has not fired, which is information too.
     */
    suspend fun watchers(perRule: Int = DEFAULT_PER_RULE): List<RuleWithFires> {
        val rules = runCatching { ruleDao.all() }.getOrDefault(emptyList())
        if (rules.isEmpty()) return emptyList()

        val firesByRule =
            rules.associate { rule ->
                rule.id to runCatching { ruleFireDao.firesForRule(rule.id, perRule) }.getOrDefault(emptyList())
            }
        val allFires = firesByRule.values.flatten()
        if (allFires.isEmpty()) return rules.map { RuleWithFires(it, emptyList()) }

        val events = eventsByUlid(allFires.map { it.itemId })
        val items = itemsById(events)
        val conditionById = rules.associate { it.id to parseCondition(it.conditionJson) }

        return rules.map { rule ->
            val fires =
                firesByRule[rule.id].orEmpty().mapNotNull { fire ->
                    val event = events[fire.itemId] ?: return@mapNotNull null
                    val item = items[fire.itemId] ?: return@mapNotNull null
                    // An unreadable condition costs the reason, not the fire: the
                    // match is stored history, so hiding it would lose the fact
                    // that the rule ran at all.
                    val condition = conditionById[rule.id]
                    RuleFire(
                        ruleId = rule.id,
                        itemId = fire.itemId,
                        matchedAt = fire.matchedAt,
                        title = event.title,
                        source = event.source,
                        itemTimestamp = event.timestamp,
                        clauses = condition?.matchedClauses(item).orEmpty(),
                    )
                }
            RuleWithFires(rule, fires)
        }
    }

    private suspend fun eventsByUlid(ulids: List<String>): Map<String, EventEntity> {
        val distinct = ulids.distinct()
        if (distinct.isEmpty()) return emptyMap()
        return runCatching { ruleFireDao.eventsByUlids(distinct) }
            .getOrDefault(emptyList())
            .associateBy { it.ulid }
    }

    private suspend fun itemsById(events: Map<String, EventEntity>): Map<String, RuleItem> {
        if (events.isEmpty()) return emptyMap()
        val seeds =
            events.values.map {
                RuleItemSeed(itemId = it.ulid, sourceId = it.source, title = it.title, content = it.content)
            }
        return runCatching { ruleWriter.itemsFor(seeds) }
            .getOrDefault(emptyList())
            .associateBy { it.id }
    }

    private fun parseCondition(json: String) = runCatching { ConditionJson.parse(json) }.getOrNull()

    companion object {
        /** Fires shown per rule before the list is collapsed. */
        const val DEFAULT_PER_RULE = 3
    }
}
