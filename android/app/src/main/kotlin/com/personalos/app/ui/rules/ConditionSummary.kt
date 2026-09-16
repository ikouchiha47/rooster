package com.personalos.app.ui.rules

import com.personalos.app.core.rules.Condition
import com.personalos.app.core.rules.ConditionJson
import com.personalos.app.core.rules.FieldOp
import com.personalos.app.core.rules.FieldValue

/**
 * Human-readable summary of a condition for the rules list and builder.
 *
 * Pure: testable without Compose.
 */
fun conditionSummary(conditionJson: String): String =
    runCatching {
        val condition = ConditionJson.parse(conditionJson)
        formatCondition(condition)
    }.getOrDefault(conditionJson)

private fun formatCondition(condition: Condition): String =
    when (condition) {
        is Condition.All -> {
            val parts = condition.conditions.map { formatCondition(it) }
            if (parts.size == 1) parts.first() else parts.joinToString(" and ")
        }
        is Condition.Any -> {
            val parts = condition.conditions.map { formatCondition(it) }
            if (parts.size == 1) parts.first() else parts.joinToString(" or ")
        }
        is Condition.Subject -> "subject is ${condition.tag}"
        is Condition.Nature -> "nature is ${condition.tag}"
        is Condition.Marker -> "marker is news"
        is Condition.Mention -> "${condition.kind} is ${condition.value}"
        is Condition.Source -> "source is ${condition.sourceId}"
        is Condition.Field -> "${condition.name} ${opSymbol(condition.op)} ${formatFieldValue(condition.value)}"
        is Condition.Text -> "text(${condition.matcher.target.serialName}) ~ \"${condition.matcher.pattern}\""
        is Condition.Crossing -> "series crossing (not previewable)"
        is Condition.Delta -> "series delta (not previewable)"
        is Condition.Min -> "series min (not previewable)"
        is Condition.Max -> "series max (not previewable)"
    }

private fun opSymbol(op: FieldOp): String =
    when (op) {
        FieldOp.EQ -> "="
        FieldOp.NE -> "!="
        FieldOp.LT -> "<"
        FieldOp.LTE -> "<="
        FieldOp.GT -> ">"
        FieldOp.GTE -> ">="
        FieldOp.CONTAINS -> "contains"
    }

private fun formatFieldValue(value: FieldValue): String =
    when (value) {
        is FieldValue.Num -> value.value.toString().removeSuffix(".0")
        is FieldValue.Str -> "\"${value.value}\""
        is FieldValue.Flag -> value.value.toString()
    }
