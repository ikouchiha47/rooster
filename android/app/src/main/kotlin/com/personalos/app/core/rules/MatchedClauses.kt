package com.personalos.app.core.rules

/**
 * Which part of a rule made it fire, with the stored value that satisfied it.
 *
 * A fire is only useful if it says *what* matched: "Hot day" alone is a
 * notification, `temp_c 40.2 > 40` is information. This reads the item's stored
 * facts and reports the clauses that actually held, so the Watchers list can
 * show the reason rather than restate the rule.
 *
 * Pure, no Android. Series predicates are deliberately **not** reported: a
 * crossing/delta is a property of a window of observations, not of this item,
 * so there is no per-item value to print. [SeriesPredicateUnsupportedException]
 * from the item evaluator is treated as "not a contribution" here; the fire
 * itself came from the series path.
 */
data class MatchedClause(
    /** `temp_c 40.2 > 40`, `text ~ "bandh"`, `subject weather`. */
    val summary: String,
)

fun Condition.matchedClauses(item: RuleItem): List<MatchedClause> = contributingLeaves(item).map { MatchedClause(it.describe(item)) }

/**
 * The leaves that held *and* were needed: `all` contributes every child that
 * held; `any` contributes only the children that held, since those are what
 * made it true.
 */
private fun Condition.contributingLeaves(item: RuleItem): List<Condition> =
    when (this) {
        is Condition.All -> conditions.flatMap { it.contributingLeaves(item) }
        is Condition.Any -> conditions.filter { it.holdsFor(item) }.flatMap { it.contributingLeaves(item) }
        else -> if (holdsFor(item)) listOf(this) else emptyList()
    }

private fun Condition.holdsFor(item: RuleItem): Boolean = runCatching { RuleEvaluator.evaluate(item, this) }.getOrDefault(false)

private fun Condition.describe(item: RuleItem): String =
    when (this) {
        is Condition.Field -> {
            val stored = item.fields[name]?.render() ?: "not stored"
            "$name $stored ${op.symbol()} ${value.render()}"
        }
        is Condition.Text -> "text ~ \"${matcher.pattern}\""
        is Condition.Subject -> "subject $tag"
        is Condition.Nature -> "nature $tag"
        is Condition.Marker -> "marker news"
        is Condition.Mention -> "$kind $value"
        is Condition.Source -> "source $sourceId"
        is Condition.Crossing, is Condition.Delta, is Condition.Min, is Condition.Max -> "series"
        is Condition.All, is Condition.Any -> "—"
    }

private fun FieldOp.symbol(): String =
    when (this) {
        FieldOp.EQ -> "="
        FieldOp.NE -> "!="
        FieldOp.LT -> "<"
        FieldOp.LTE -> "<="
        FieldOp.GT -> ">"
        FieldOp.GTE -> ">="
        FieldOp.CONTAINS -> "contains"
    }

/** The stored value as a person reads it: no trailing `.0` on whole numbers. */
internal fun FieldValue.render(): String =
    when (this) {
        is FieldValue.Num -> if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
        is FieldValue.Str -> value
        is FieldValue.Flag -> value.toString()
    }
