package com.personalos.app.core.catalog

import com.personalos.app.core.rules.FieldValue

/**
 * ADR 0005: the closed op matrix — type x measure selects the allowed ops.
 * Pure Kotlin; the UI reads [allowed], the writer enforces [require].
 */
object OpMatrix {
    private val itemNumeric = setOf("eq", "ne", "lt", "lte", "gt", "gte")
    private val series = setOf("crossing", "delta", "min", "max")
    private val reserved = setOf("p50", "p95", "count")

    /** Every op token the catalog language knows, including reserved ones. */
    val knownOps: Set<String> =
        itemNumeric + setOf("contains", "in") + series + reserved

    /** Defaults per type x measure, so seeds state intent without restating the matrix. */
    fun defaults(
        valueType: ValueType,
        measure: MeasureKind,
    ): Set<String> =
        when (measure) {
            MeasureKind.HISTOGRAM -> emptySet()
            MeasureKind.COUNTER ->
                when (valueType) {
                    ValueType.NUM -> itemNumeric
                    ValueType.STR -> setOf("eq", "ne", "contains")
                    ValueType.ENUM -> setOf("eq", "in")
                    ValueType.FLAG -> setOf("eq")
                }
            MeasureKind.GAUGE ->
                when (valueType) {
                    ValueType.NUM -> itemNumeric + series
                    ValueType.STR -> setOf("eq", "ne", "contains")
                    ValueType.ENUM -> setOf("eq", "in")
                    ValueType.FLAG -> setOf("eq")
                }
        }

    fun allowed(
        facet: Facet,
        op: String,
    ): Boolean {
        if (op in reserved) return false
        if (facet.measure == MeasureKind.HISTOGRAM) return false
        return op in facet.ops && op in defaults(facet.valueType, facet.measure)
    }

    /**
     * @throws MeasureUnsupportedException for reserved ops or histogram facets.
     * @throws IllegalArgumentException for disallowed ops or value kind mismatches.
     */
    fun require(
        facet: Facet,
        op: String,
        value: FieldValue,
    ) {
        if (op in reserved) {
            throw MeasureUnsupportedException("op '$op' on '${facet.id}' is reserved, not implemented")
        }
        if (facet.measure == MeasureKind.HISTOGRAM) {
            throw MeasureUnsupportedException("facet '${facet.id}' is a histogram: no ops implemented")
        }
        require(op in facet.ops) {
            "facet '${facet.id}' does not offer op '$op'; allowed: ${facet.ops.sorted()}"
        }
        require(op in defaults(facet.valueType, facet.measure)) {
            "op '$op' is not valid for ${facet.valueType} x ${facet.measure} (facet '${facet.id}')"
        }
        val ok =
            when (facet.valueType) {
                ValueType.NUM -> value is FieldValue.Num
                ValueType.STR -> value is FieldValue.Str
                ValueType.ENUM -> value is FieldValue.Str
                ValueType.FLAG -> value is FieldValue.Flag
            }
        require(ok) {
            "facet '${facet.id}' is ${facet.valueType} but got ${value.kindName()}"
        }
    }

    private fun FieldValue.kindName(): String =
        when (this) {
            is FieldValue.Num -> "a number"
            is FieldValue.Str -> "a string"
            is FieldValue.Flag -> "a boolean"
        }
}
