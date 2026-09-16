package com.personalos.app.core.rules

/**
 * The rule condition language (ADR 0003 §6, plan slice 2 tasks T2.1-T2.3).
 *
 * A condition is a tree: `all` / `any` composition over predicate leaves. Item
 * predicates read one item; series predicates read a *window* of a series
 * source, not one item (ADR §8). This package models, parses and validates the
 * language only — evaluation against items is slice 3, so nothing here touches
 * Android, storage or the ingest path.
 */
sealed interface Condition {
    /** Holds only when every child holds. */
    data class All(
        val conditions: List<Condition>,
    ) : Condition

    /** Holds when at least one child holds. */
    data class Any(
        val conditions: List<Condition>,
    ) : Condition

    /** A tag in the subject group ([com.personalos.app.core.tag.TagGroups.SUBJECTS]). */
    data class Subject(
        val tag: String,
    ) : Condition

    /** A tag in the nature group ([com.personalos.app.core.tag.TagGroups.NATURES]). */
    data class Nature(
        val tag: String,
    ) : Condition

    /** The structural marker tag (`news`). */
    data object Marker : Condition

    /** A stored mention row: its kind (`place`, `party`) and value. */
    data class Mention(
        val kind: String,
        val value: String,
    ) : Condition

    /** A stored source row, by id. */
    data class Source(
        val sourceId: String,
    ) : Condition

    /** A typed extra field on the item (`amount > 10000`). */
    data class Field(
        val name: String,
        val op: FieldOp,
        val value: FieldValue,
    ) : Condition

    /** Stored text, matched with a Unicode-bounded regex. */
    data class Text(
        val matcher: TextMatcher,
    ) : Condition

    // ------------------------------------------------------- series predicates
    // These read a window of a series source (ADR §8), not one item. Nothing
    // evaluates them yet; the language must carry them faithfully.

    /** The series crosses [threshold] in [direction] somewhere in the window. */
    data class Crossing(
        val field: String,
        val direction: CrossingDirection,
        val threshold: Double,
        val window: Window,
    ) : Condition

    /** The series changes by at least [by] (signed: negative is a drop) across the window. */
    data class Delta(
        val field: String,
        val by: Double,
        val window: Window,
    ) : Condition

    /** The smallest [field] in the window compares [op] [value]. */
    data class Min(
        val field: String,
        val op: FieldOp,
        val value: Double,
        val window: Window,
    ) : Condition

    /** The largest [field] in the window compares [op] [value]. */
    data class Max(
        val field: String,
        val op: FieldOp,
        val value: Double,
        val window: Window,
    ) : Condition
}

/** The number of most recent observations a series predicate reads. Always > 0. */
data class Window(
    val observations: Int,
)

/** Which way a series crosses a threshold. */
enum class CrossingDirection(
    val serialName: String,
) {
    BELOW("below"),
    ABOVE("above"),
    ;

    companion object {
        fun from(value: String): CrossingDirection? = entries.firstOrNull { it.serialName == value }
    }
}

/** A typed field comparison. `lt`/`lte`/`gt`/`gte` are numeric; `contains` is textual. */
enum class FieldOp(
    val serialName: String,
) {
    EQ("eq"),
    NE("ne"),
    LT("lt"),
    LTE("lte"),
    GT("gt"),
    GTE("gte"),
    CONTAINS("contains"),
    ;

    /** True for the operators that only compare numbers (all but `eq`, `ne`, `contains`). */
    val isNumeric: Boolean
        get() =
            when (this) {
                LT, LTE, GT, GTE -> true
                EQ, NE, CONTAINS -> false
            }

    companion object {
        fun from(value: String): FieldOp? = entries.firstOrNull { it.serialName == value }
    }
}

/** A typed field value. Numbers, strings and booleans are the three kinds `fields` carries. */
sealed interface FieldValue {
    data class Num(
        val value: Double,
    ) : FieldValue

    data class Str(
        val value: String,
    ) : FieldValue

    data class Flag(
        val value: Boolean,
    ) : FieldValue
}

/**
 * True when a [Condition.Text] predicate appears anywhere in the tree, recursing
 * through nested `all` / `any` (ADR §10, plan T2.5). Enrichment re-evaluates
 * *only* these rules, so a missed recursion silently breaks that later.
 */
fun Condition.isEnrichmentSensitive(): Boolean =
    when (this) {
        is Condition.All -> conditions.any { it.isEnrichmentSensitive() }
        is Condition.Any -> conditions.any { it.isEnrichmentSensitive() }
        is Condition.Text -> true
        else -> false
    }
