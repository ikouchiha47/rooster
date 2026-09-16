package com.personalos.app.core.rules

import com.personalos.app.core.tag.TagGroups

/**
 * Thrown when a condition cannot be evaluated against a single item because it
 * contains a **series predicate**.
 *
 * Series predicates read a window of stored observations, not one item (ADR
 * 0003 §8), and slice 5 supplies that window. The item evaluator refuses such a
 * condition rather than answering `false`, because `false` would be a silent
 * no-op: "not implemented yet" would look exactly like "this rule never
 * matches", which is the substitutability failure the design guidelines forbid.
 */
class SeriesPredicateUnsupportedException(
    message: String,
) : UnsupportedOperationException(message)

/**
 * The pure item evaluator (ADR 0003 §6–§7, §12; plan T3.1). It reads one
 * [RuleItem] and answers whether a parsed [Condition] holds. It is Kotlin only —
 * no `android.*` import — and it never fetches, reads the store or writes
 * anything: rules evaluate only over what is already stored (§7).
 *
 * ## Query structure (§12): what belongs here, and what does not
 *
 * The coarse filter — tag / source / date window / mention — belongs in **SQL**,
 * on indexed columns, so a caller never loads the whole store to evaluate.
 * Regex and typed comparisons belong **here**: a regex cannot be indexed, and
 * interpolating a user pattern into SQL is worse than slow. A caller running a
 * rule over history therefore narrows with SQL first and hands the survivors
 * here; this object is the authoritative matcher for rules, structured search
 * and the dry run alike, so those cannot diverge. Slice 5 wires that coarse
 * query — this evaluator is deliberately ignorant of it.
 *
 * ## Series predicates fail loudly
 *
 * `crossing`, `delta` and `min` / `max` read a *window* of a series source (ADR
 * §8); the windows arrive in slice 5. Until then [evaluate] rejects any such
 * condition with [SeriesPredicateUnsupportedException]. The rejection walks the
 * whole tree **before** evaluating, so `all` cannot short-circuit past a series
 * child and hide it behind a false sibling.
 *
 * ## Field semantics
 *
 * A predicate over a [RuleItem.fields] name that is absent matches nothing, for
 * every operator including `ne`: "the field is not 5" is not a claim we can make
 * about an item whose field was never stored.
 *
 * ## Tag groups
 *
 * Subject and nature membership is a plain tag lookup — [ConditionJson] already
 * validated the tag against [TagGroups] at write time — and the marker reads
 * [TagGroups.MARKERS], so the taxonomy keeps one owner and no group is
 * re-derived here.
 */
object RuleEvaluator {
    /** True when [condition] holds for [item]; rejects series predicates first. */
    fun evaluate(
        item: RuleItem,
        condition: Condition,
    ): Boolean {
        rejectSeriesPredicates(condition)
        return holds(item, condition)
    }

    /**
     * Throws [SeriesPredicateUnsupportedException] when [condition] contains a
     * series predicate anywhere in its tree. Exposed so a caller can validate a
     * rule once rather than per item.
     */
    fun requireItemEvaluable(condition: Condition) {
        rejectSeriesPredicates(condition)
    }

    private fun rejectSeriesPredicates(condition: Condition) {
        when (condition) {
            is Condition.All -> condition.conditions.forEach(::rejectSeriesPredicates)
            is Condition.Any -> condition.conditions.forEach(::rejectSeriesPredicates)
            is Condition.Crossing -> throw series(condition.field, condition.window, "crossing")
            is Condition.Delta -> throw series(condition.field, condition.window, "delta")
            is Condition.Min -> throw series(condition.field, condition.window, "min")
            is Condition.Max -> throw series(condition.field, condition.window, "max")
            else -> Unit
        }
    }

    private fun series(
        field: String,
        window: Window,
        predicate: String,
    ): SeriesPredicateUnsupportedException =
        SeriesPredicateUnsupportedException(
            "$predicate on '$field' reads a window of ${window.observations} stored observations; " +
                "series predicates are evaluated by the monitor engine (ADR 0003 §8, plan slice 5), " +
                "not by the item evaluator",
        )

    private fun holds(
        item: RuleItem,
        condition: Condition,
    ): Boolean =
        when (condition) {
            is Condition.All -> condition.conditions.all { holds(item, it) }
            is Condition.Any -> condition.conditions.any { holds(item, it) }
            is Condition.Subject -> condition.tag in item.tags
            is Condition.Nature -> condition.tag in item.tags
            is Condition.Marker -> item.tags.any { it in TagGroups.MARKERS }
            is Condition.Mention -> item.mentions.any { it.kind == condition.kind && it.value == condition.value }
            is Condition.Source -> item.sourceId == condition.sourceId
            is Condition.Field -> fieldHolds(item.fields[condition.name], condition.op, condition.value)
            is Condition.Text -> textHolds(item, condition.matcher)
            // Unreachable: rejectSeriesPredicates ran first. Kept so the `when`
            // stays exhaustive and a future call path cannot fall through to false.
            is Condition.Crossing -> throw series(condition.field, condition.window, "crossing")
            is Condition.Delta -> throw series(condition.field, condition.window, "delta")
            is Condition.Min -> throw series(condition.field, condition.window, "min")
            is Condition.Max -> throw series(condition.field, condition.window, "max")
        }

    private fun textHolds(
        item: RuleItem,
        matcher: TextMatcher,
    ): Boolean =
        when (matcher.target) {
            TextTarget.TITLE -> matcher.matches(item.title)
            TextTarget.CONTENT -> matcher.matches(item.content)
            TextTarget.ANY -> matcher.matches(item.title) || matcher.matches(item.content)
        }

    private fun fieldHolds(
        stored: FieldValue?,
        op: FieldOp,
        expected: FieldValue,
    ): Boolean {
        if (stored == null) return false
        return when (op) {
            FieldOp.EQ -> stored == expected
            FieldOp.NE -> stored != expected
            FieldOp.LT -> numeric(stored, expected) { a, b -> a < b }
            FieldOp.LTE -> numeric(stored, expected) { a, b -> a <= b }
            FieldOp.GT -> numeric(stored, expected) { a, b -> a > b }
            FieldOp.GTE -> numeric(stored, expected) { a, b -> a >= b }
            FieldOp.CONTAINS -> {
                val text = (stored as? FieldValue.Str)?.value ?: return false
                val needle = (expected as? FieldValue.Str)?.value ?: return false
                text.contains(needle)
            }
        }
    }

    private inline fun numeric(
        stored: FieldValue,
        expected: FieldValue,
        test: (Double, Double) -> Boolean,
    ): Boolean {
        val a = (stored as? FieldValue.Num)?.value ?: return false
        val b = (expected as? FieldValue.Num)?.value ?: return false
        return test(a, b)
    }
}
