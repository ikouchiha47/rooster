package com.personalos.app.data

import androidx.room.ColumnInfo
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

/**
 * One fire joined with its item's text and time — the row a page query returns.
 * `content` travels with it because a fire's *reason* is recomputed from the item.
 */
data class RuleFireCandidate(
    @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "matched_at") val matchedAt: Long,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "item_timestamp") val itemTimestamp: Long,
)

/** One page of a rule's fires, and where the next page starts (null = no more). */
data class RuleFirePage(
    val fires: List<RuleFire>,
    val nextTs: Long?,
    val nextId: String?,
) {
    val hasMore: Boolean get() = nextTs != null
}

/** A rule and its most recent fires, newest first. */
data class RuleWithFires(
    val rule: RuleEntity,
    val fires: List<RuleFire>,
    /** Every fire the rule has, so a section can say how many it is not showing. */
    val total: Int = fires.size,
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
        val totals =
            rules.associate { rule ->
                rule.id to runCatching { ruleFireDao.countForRule(rule.id) }.getOrDefault(0)
            }
        val allFires = firesByRule.values.flatten()
        if (allFires.isEmpty()) return rules.map { RuleWithFires(it, emptyList(), totals[it.id] ?: 0) }

        val events = eventsByUlid(allFires.map { it.itemId })
        val items = itemsById(events)

        return rules.map { rule ->
            RuleWithFires(
                rule = rule,
                fires = toFires(rule, firesByRule[rule.id].orEmpty(), events, items),
                total = totals[rule.id] ?: 0,
            )
        }
    }

    /**
     * One keyset page of a rule's fires, for the Watchers "more" tap. The cursor
     * is the last fire of the previous page — `(nextTs, nextId)` from a
     * [RuleFirePage] — or a null [cursorTs] to start. Reads one row more than
     * [limit] to know whether another page exists, so the UI never offers a
     * "more" that returns nothing.
     */
    suspend fun forRulePage(
        ruleId: String,
        cursorTs: Long?,
        cursorId: String,
        limit: Int = PAGE_SIZE,
    ): RuleFirePage {
        val rule =
            runCatching { ruleDao.all() }
                .getOrDefault(emptyList())
                .firstOrNull { it.id == ruleId } ?: return RuleFirePage(emptyList(), null, null)
        val raw =
            runCatching { ruleFireDao.firesForRulePage(ruleId, cursorTs, cursorId, limit + 1) }
                .getOrDefault(emptyList())
        if (raw.isEmpty()) return RuleFirePage(emptyList(), null, null)

        val page = raw.take(limit)
        val items = itemsFor(page.map { RuleItemSeed(it.itemId, it.source, it.title, it.content) })
        val condition = parseCondition(rule.conditionJson)
        val fires =
            page.mapNotNull { candidate ->
                val item = items[candidate.itemId] ?: return@mapNotNull null
                RuleFire(
                    ruleId = rule.id,
                    itemId = candidate.itemId,
                    matchedAt = candidate.matchedAt,
                    title = candidate.title,
                    source = candidate.source,
                    itemTimestamp = candidate.itemTimestamp,
                    clauses = condition?.matchedClauses(item).orEmpty(),
                )
            }
        val more = raw.size > limit
        return RuleFirePage(
            fires = fires,
            nextTs = page.last().itemTimestamp.takeIf { more },
            nextId = page.last().itemId.takeIf { more },
        )
    }

    /**
     * Maps stored fires to the feed's shape. An unreadable condition costs the
     * *reason*, not the fire: the match is stored history, so hiding it would lose
     * the fact that the rule ran at all.
     */
    private fun toFires(
        rule: RuleEntity,
        raw: List<ItemRuleEntity>,
        events: Map<String, EventEntity>,
        items: Map<String, RuleItem>,
    ): List<RuleFire> {
        val condition = parseCondition(rule.conditionJson)
        return raw.mapNotNull { fire ->
            val event = events[fire.itemId] ?: return@mapNotNull null
            val item = items[fire.itemId] ?: return@mapNotNull null
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
    }

    private suspend fun eventsByUlid(ulids: List<String>): Map<String, EventEntity> {
        val distinct = ulids.distinct()
        if (distinct.isEmpty()) return emptyMap()
        return runCatching { ruleFireDao.eventsByUlids(distinct) }
            .getOrDefault(emptyList())
            .associateBy { it.ulid }
    }

    private suspend fun itemsById(events: Map<String, EventEntity>): Map<String, RuleItem> = itemsFor(events.values.map { RuleItemSeed(it.ulid, it.source, it.title, it.content) })

    private suspend fun itemsFor(seeds: List<RuleItemSeed>): Map<String, RuleItem> {
        if (seeds.isEmpty()) return emptyMap()
        return runCatching { ruleWriter.itemsFor(seeds) }
            .getOrDefault(emptyList())
            .associateBy { it.id }
    }

    private fun parseCondition(json: String) = runCatching { ConditionJson.parse(json) }.getOrNull()

    companion object {
        /** Fires shown per rule collapsed; the rest are one "more" tap away. */
        const val DEFAULT_PER_RULE = 5

        /** Fires fetched per "more" tap — incremental, never the whole rule at once. */
        const val PAGE_SIZE = 20
    }
}
