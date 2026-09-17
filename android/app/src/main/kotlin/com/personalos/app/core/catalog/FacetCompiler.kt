package com.personalos.app.core.catalog

import com.personalos.app.core.rules.Condition
import com.personalos.app.core.rules.CrossingDirection
import com.personalos.app.core.rules.FieldOp
import com.personalos.app.core.rules.FieldValue
import com.personalos.app.core.rules.TextMatcher
import com.personalos.app.core.rules.TextTarget
import com.personalos.app.core.rules.Window

/**
 * ADR 0005: the only place that maps catalog facets onto [Condition] leaves.
 * Subject / nature / marker stay tag leaves; title / content stay `text`;
 * place / party stay mentions; everything else stays `field` or a series leaf.
 * Pure Kotlin — the UI compiles drafts through here, never by hand.
 */
sealed interface FacetClause {
    val facetId: String
    val op: String

    data class Single(
        override val facetId: String,
        override val op: String,
        val value: FieldValue,
        val window: Int? = null,
        val direction: String? = null,
        val compareOp: String? = null,
    ) : FacetClause

    data class Multi(
        override val facetId: String,
        override val op: String = "in",
        val values: List<FieldValue>,
    ) : FacetClause
}

object FacetCompiler {
    fun toCondition(
        clause: FacetClause,
        catalog: Catalog,
    ): Condition =
        when (clause) {
            is FacetClause.Multi -> multi(clause, catalog)
            is FacetClause.Single -> single(clause, catalog)
        }

    private fun multi(
        clause: FacetClause.Multi,
        catalog: Catalog,
    ): Condition {
        val facet =
            catalog.facet(clause.facetId)
                ?: throw IllegalArgumentException("unknown facet '${clause.facetId}'")
        require(clause.op == "in") {
            "facet '${facet.id}': multi-value only supports 'in', was '${clause.op}'"
        }
        require(clause.values.isNotEmpty()) {
            "facet '${facet.id}': 'in' needs at least one value"
        }
        clause.values.forEach { OpMatrix.require(facet, "in", it) }
        val singles =
            clause.values.map { value ->
                single(FacetClause.Single(facetId = facet.id, op = "eq", value = value), catalog)
            }
        return if (singles.size == 1) singles.first() else Condition.Any(singles)
    }

    private fun single(
        clause: FacetClause.Single,
        catalog: Catalog,
    ): Condition {
        val facet =
            catalog.facet(clause.facetId)
                ?: throw IllegalArgumentException("unknown facet '${clause.facetId}'")
        OpMatrix.require(facet, clause.op, clause.value)
        return when (facet.id) {
            "subject" -> {
                require(clause.op == "eq") { "subject: only 'eq' compiles to a tag leaf" }
                Condition.Subject((clause.value as FieldValue.Str).value)
            }
            "nature" -> {
                require(clause.op == "eq") { "nature: only 'eq' compiles to a tag leaf" }
                Condition.Nature((clause.value as FieldValue.Str).value)
            }
            "marker" -> {
                require(clause.op == "eq") { "marker: only 'eq' compiles to a tag leaf" }
                val flag = (clause.value as FieldValue.Flag).value
                require(flag) { "marker: must be true (there is no negation)" }
                Condition.Marker
            }
            "source" -> {
                require(clause.op == "eq") { "source: only 'eq' compiles to a source leaf" }
                Condition.Source((clause.value as FieldValue.Str).value)
            }
            "title" -> {
                require(clause.op == "contains") { "title: only 'contains' compiles to text" }
                Condition.Text(TextMatcher((clause.value as FieldValue.Str).value, TextTarget.TITLE))
            }
            "content" -> {
                require(clause.op == "contains") { "content: only 'contains' compiles to text" }
                Condition.Text(TextMatcher((clause.value as FieldValue.Str).value, TextTarget.CONTENT))
            }
            "place" -> {
                require(clause.op == "eq") { "place: only 'eq' compiles to a mention" }
                Condition.Mention(kind = "place", value = (clause.value as FieldValue.Str).value)
            }
            "party" -> {
                require(clause.op == "eq") { "party: only 'eq' compiles to a mention" }
                Condition.Mention(kind = "party", value = (clause.value as FieldValue.Str).value)
            }
            else -> generic(facet, clause)
        }
    }

    private fun generic(
        facet: Facet,
        clause: FacetClause.Single,
    ): Condition =
        when (clause.op) {
            "crossing" -> {
                val window = requireWindow(clause)
                val direction =
                    CrossingDirection.from(clause.direction ?: "")
                        ?: throw IllegalArgumentException(
                            "facet '${facet.id}': crossing needs direction below or above",
                        )
                Condition.Crossing(
                    field = facet.id,
                    direction = direction,
                    threshold = (clause.value as FieldValue.Num).value,
                    window = Window(window),
                )
            }
            "delta" ->
                Condition.Delta(
                    field = facet.id,
                    by = (clause.value as FieldValue.Num).value,
                    window = Window(requireWindow(clause)),
                )
            "min", "max" -> {
                val compare =
                    FieldOp.from(clause.compareOp ?: "")
                        ?: throw IllegalArgumentException(
                            "facet '${facet.id}': '${clause.op}' needs compareOp lt/lte/gt/gte",
                        )
                require(compare.isNumeric) {
                    "facet '${facet.id}': '${clause.op}' compareOp must be numeric, was ${compare.serialName}"
                }
                val threshold = (clause.value as FieldValue.Num).value
                val window = Window(requireWindow(clause))
                if (clause.op == "min") {
                    Condition.Min(field = facet.id, op = compare, value = threshold, window = window)
                } else {
                    Condition.Max(field = facet.id, op = compare, value = threshold, window = window)
                }
            }
            else -> {
                val op =
                    FieldOp.from(clause.op)
                        ?: throw IllegalArgumentException("facet '${facet.id}': unknown op '${clause.op}'")
                Condition.Field(name = facet.id, op = op, value = clause.value)
            }
        }

    private fun requireWindow(clause: FacetClause.Single): Int {
        val window = clause.window
        require(window != null && window > 0) {
            "facet '${clause.facetId}': op '${clause.op}' needs window > 0"
        }
        return window
    }
}
