package com.personalos.app.core.rules

/**
 * ADR 0005 T18: ingest dispatch — pure. Item rules run on [RuleEvaluator],
 * series rules on [SeriesEvaluator]. A series condition never reaches the item
 * evaluator (that throws); series rules are never skipped. Dispatch walks the
 * condition tree, never the kind name.
 */
object RuleDispatcher {
    data class Partition(
        val item: List<DispatchableRule>,
        val series: List<DispatchableRule>,
    )

    data class DispatchableRule(
        val id: String,
        val condition: Condition,
    )

    fun partition(rules: List<DispatchableRule>): Partition {
        val item = ArrayList<DispatchableRule>()
        val series = ArrayList<DispatchableRule>()
        for (rule in rules) {
            if (rule.condition.hasSeriesPredicate()) series += rule else item += rule
        }
        return Partition(item = item, series = series)
    }
}
