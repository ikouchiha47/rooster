package com.personalos.app.data

import android.util.Log
import com.personalos.app.core.rules.Condition
import com.personalos.app.core.rules.ConditionJson
import com.personalos.app.core.rules.FieldValue
import com.personalos.app.core.rules.RuleEvaluator
import com.personalos.app.core.rules.RuleItem
import com.personalos.app.core.rules.RuleMention
import com.personalos.app.core.rules.isEnrichmentSensitive

/**
 * The item facts a caller hands to rule evaluation.
 *
 * Tags and mentions are deliberately **absent**: [RuleWriter] reads those from
 * the store, so the store stays the one owner of tag and mention facts and
 * evaluation sees exactly what is persisted (ADR 0003 §7 — rules evaluate what
 * is already stored).
 */
data class RuleItemSeed(
    /** `events.ulid`. */
    val itemId: String,
    /** `events.source` — the source's identity, not its transport. */
    val sourceId: String,
    val title: String,
    val content: String,
    /** Typed extras, when a kind supplies them. Empty until fields are stored (slice 4). */
    val fields: Map<String, FieldValue> = emptyMap(),
)

/**
 * The result of a dry run: the ids of items that would match. Persisted nowhere —
 * it is a returned value, not a writer call (R4, ADR §10).
 */
data class RuleDryRunResult(
    val matchedItemIds: List<String>,
) {
    val count: Int get() = matchedItemIds.size
}

/**
 * Writes rule matches for newly landed or newly enriched items. Mirrors
 * [TagWriter]:
 *  - rule evaluation **never blocks ingest or enrichment**: a rule whose
 *    condition is unreadable, or is not evaluable over items yet, is logged and
 *    skipped rather than thrown at the caller or allowed to drop the item;
 *  - rows are append-only. [ItemRuleDao] inserts with `IGNORE`, so re-running is
 *    safe and the first write wins (R5).
 *
 * The three write paths match ADR §10's re-evaluation policy: [writeAll] on
 * ingest, [reevaluateEnriched] on enrichment (only text-predicate rules, only
 * the enriched items), and [dryRun] on rule edit — which persists nothing.
 */
class RuleWriter(
    private val ruleDao: RuleDao,
    private val matchDao: ItemRuleDao,
    private val tagDao: ItemTagDao,
    private val mentionDao: MentionDao,
) {
    /** Evaluates every enabled rule against newly landed items; appends matches. */
    suspend fun writeAll(
        seeds: List<RuleItemSeed>,
        now: Long = System.currentTimeMillis(),
    ): Int {
        if (seeds.isEmpty()) return 0
        val rules = enabledRules()
        if (rules.isEmpty()) return 0
        return insertMatches(rules, materialise(seeds), now)
    }

    /** One item's verdict; see [writeAll]. */
    suspend fun write(
        seed: RuleItemSeed,
        now: Long = System.currentTimeMillis(),
    ): Int = writeAll(listOf(seed), now)

    /**
     * Re-evaluates **only** rules whose condition contains a text predicate
     * anywhere (ADR §10, R3), against **only** the items whose text just grew.
     * Called by the enrichment path; tags and mentions are read from the store.
     */
    suspend fun reevaluateEnriched(
        seeds: List<RuleItemSeed>,
        now: Long = System.currentTimeMillis(),
    ): Int {
        if (seeds.isEmpty()) return 0
        val rules = enabledRules().filter { it.condition.isEnrichmentSensitive() }
        if (rules.isEmpty()) return 0
        return insertMatches(rules, materialise(seeds), now)
    }

    /**
     * Evaluates [rule] over supplied history and returns what would match,
     * writing nothing (R4, ADR §10). The return value *is* the API: there is no
     * writer call a caller could accidentally make. A condition that cannot be
     * evaluated — a series predicate, or malformed JSON — throws rather than
     * answering `false`, so an unimplemented rule cannot read as "no matches".
     *
     * The caller owns the coarse SQL filter (tag / source / date / mention, ADR
     * §12); this method evaluates whatever it is handed.
     */
    suspend fun dryRun(
        rule: RuleEntity,
        seeds: List<RuleItemSeed>,
    ): RuleDryRunResult {
        val condition = ConditionJson.parse(rule.conditionJson)
        RuleEvaluator.requireItemEvaluable(condition)
        val matches = materialise(seeds).filter { RuleEvaluator.evaluate(it, condition) }
        return RuleDryRunResult(matches.map { it.id })
    }

    /** Enabled rules with their parsed conditions; unreadable rows are logged and skipped. */
    private suspend fun enabledRules(): List<EvaluableRule> =
        runCatching { ruleDao.all() }
            .onFailure { Log.w(TAG, "rule load failed", it) }
            .getOrNull()
            .orEmpty()
            .filter { it.enabled }
            .mapNotNull { rule ->
                runCatching { ConditionJson.parse(rule.conditionJson) }
                    .onFailure { Log.w(TAG, "rule ${rule.id} has an unreadable condition; skipped", it) }
                    .getOrNull()
                    ?.let { EvaluableRule(rule.id, it) }
            }

    /**
     * Applies each rule to each item, then one insert for the whole batch.
     *
     * A condition that cannot be evaluated (a series predicate today) is caught
     * per rule and logged at warning level — loud in the log, never a crash in
     * ingest, and never a `false` that would look like a rule that never matches.
     * Returns the number of rows actually written (`IGNORE` drops duplicates).
     */
    private suspend fun insertMatches(
        rules: List<EvaluableRule>,
        items: List<RuleItem>,
        now: Long,
    ): Int {
        if (items.isEmpty()) return 0
        val rows = ArrayList<ItemRuleEntity>()
        for (rule in rules) {
            val matched =
                runCatching {
                    RuleEvaluator.requireItemEvaluable(rule.condition)
                    items.filter { RuleEvaluator.evaluate(it, rule.condition) }
                }.onFailure { Log.w(TAG, "rule ${rule.id} is not evaluable over items; skipped", it) }
                    .getOrNull()
                    ?: continue
            matched.forEach { item ->
                rows += ItemRuleEntity(itemId = item.id, ruleId = rule.id, matchedAt = now)
            }
        }
        if (rows.isEmpty()) return 0
        val rowIds =
            runCatching { matchDao.insertAll(rows) }
                .onFailure { Log.w(TAG, "match insert failed for ${rows.size} rows", it) }
                .getOrNull()
                ?: return 0
        return rowIds.count { it != -1L }
    }

    /** Loads the stored tags and mentions for the seeds, then builds evaluator inputs. */
    private suspend fun materialise(seeds: List<RuleItemSeed>): List<RuleItem> {
        if (seeds.isEmpty()) return emptyList()
        val ids = seeds.map { it.itemId }
        val tags =
            runCatching { tagDao.tagsForItems(ids) }
                .onFailure { Log.w(TAG, "tag load failed for ${ids.size} items", it) }
                .getOrNull()
                .orEmpty()
                .groupBy({ it.itemId }, { it.tag })
        val mentions =
            runCatching { mentionDao.forItems(ids) }
                .onFailure { Log.w(TAG, "mention load failed for ${ids.size} items", it) }
                .getOrNull()
                .orEmpty()
                .groupBy { it.itemId }
        return seeds.map { seed ->
            RuleItem(
                id = seed.itemId,
                sourceId = seed.sourceId,
                title = seed.title,
                content = seed.content,
                tags = tags[seed.itemId].orEmpty().toSet(),
                mentions = mentions[seed.itemId].orEmpty().map { RuleMention(it.kind, it.surface) },
                fields = seed.fields,
            )
        }
    }

    private data class EvaluableRule(
        val id: String,
        val condition: Condition,
    )

    private companion object {
        const val TAG = "Rules"
    }
}

/** The evaluation-seed view of a just-landed row. */
fun EventEntity.toRuleItemSeed(fields: Map<String, FieldValue> = emptyMap()): RuleItemSeed =
    RuleItemSeed(
        itemId = ulid,
        sourceId = source,
        title = title,
        content = content,
        fields = fields,
    )
