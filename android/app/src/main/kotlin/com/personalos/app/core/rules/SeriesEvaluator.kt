package com.personalos.app.core.rules

import com.personalos.app.core.catalog.MeasureKind
import com.personalos.app.core.catalog.MeasureUnsupportedException

/**
 * ADR 0005: the pure series evaluator — reads a window of stored observations,
 * never one item. Level (`field gt`) lives in [RuleEvaluator]; edge
 * (`crossing`) lives here. A window already entirely above the threshold is
 * not a crossing. Pure Kotlin, no `android.*`, no store access: the caller
 * supplies the last N values.
 */
object SeriesEvaluator {
    fun evaluate(
        values: List<Double>,
        condition: Condition,
        measure: MeasureKind = MeasureKind.GAUGE,
    ): Boolean {
        if (measure != MeasureKind.GAUGE) {
            throw MeasureUnsupportedException(
                "series ops need a gauge facet, was $measure",
            )
        }
        return when (condition) {
            is Condition.Crossing -> crossing(values, condition)
            is Condition.Delta -> delta(values, condition)
            is Condition.Min -> aggregate(values.minOrNull(), condition.op, condition.value)
            is Condition.Max -> aggregate(values.maxOrNull(), condition.op, condition.value)
            else -> throw IllegalArgumentException(
                "SeriesEvaluator handles only crossing/delta/min/max, was ${condition.kindName()}",
            )
        }
    }

    private fun crossing(
        values: List<Double>,
        condition: Condition.Crossing,
    ): Boolean {
        if (values.size < 2) return false
        return when (condition.direction) {
            CrossingDirection.ABOVE ->
                values.indices.any { i ->
                    values[i] <= condition.threshold && ((i + 1) until values.size).any { j -> values[j] > condition.threshold }
                }
            CrossingDirection.BELOW ->
                values.indices.any { i ->
                    values[i] >= condition.threshold && ((i + 1) until values.size).any { j -> values[j] < condition.threshold }
                }
        }
    }

    private fun delta(
        values: List<Double>,
        condition: Condition.Delta,
    ): Boolean {
        if (values.size < 2) return false
        val change = values.last() - values.first()
        return if (condition.by >= 0) change >= condition.by else change <= condition.by
    }

    private fun aggregate(
        aggregate: Double?,
        op: FieldOp,
        threshold: Double,
    ): Boolean {
        if (aggregate == null) return false
        return when (op) {
            FieldOp.LT -> aggregate < threshold
            FieldOp.LTE -> aggregate <= threshold
            FieldOp.GT -> aggregate > threshold
            FieldOp.GTE -> aggregate >= threshold
            FieldOp.EQ -> aggregate == threshold
            FieldOp.NE -> aggregate != threshold
            FieldOp.CONTAINS -> false
        }
    }

    private fun Condition.kindName(): String =
        when (this) {
            is Condition.All -> "all"
            is Condition.Any -> "any"
            is Condition.Subject -> "subject"
            is Condition.Nature -> "nature"
            is Condition.Marker -> "marker"
            is Condition.Mention -> "mention"
            is Condition.Source -> "source"
            is Condition.Field -> "field"
            is Condition.Text -> "text"
            is Condition.Crossing -> "crossing"
            is Condition.Delta -> "delta"
            is Condition.Min -> "min"
            is Condition.Max -> "max"
        }
}
