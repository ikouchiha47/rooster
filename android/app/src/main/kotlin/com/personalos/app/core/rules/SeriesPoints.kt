package com.personalos.app.core.rules

/**
 * ADR 0005 T14: timestamped series reads — pure, no Android, no store.
 * The data layer supplies the last N points newest-first or oldest-first;
 * evaluation never scans beyond [Window.observations].
 */
data class SeriesPoint(
    val timestampMs: Long,
    val value: Double,
    /** True when the producer recorded a gap after this point. */
    val gapAfter: Boolean = false,
)

class InsufficientSeriesException(
    message: String,
) : IllegalStateException(message)

/** True when a series predicate appears anywhere in the tree. */
fun Condition.hasSeriesPredicate(): Boolean =
    when (this) {
        is Condition.All -> conditions.any { it.hasSeriesPredicate() }
        is Condition.Any -> conditions.any { it.hasSeriesPredicate() }
        is Condition.Crossing, is Condition.Delta, is Condition.Min, is Condition.Max -> true
        else -> false
    }

/** Every series leaf in the tree, for window loading. */
fun Condition.seriesLeaves(): List<Condition> =
    when (this) {
        is Condition.All -> conditions.flatMap { it.seriesLeaves() }
        is Condition.Any -> conditions.flatMap { it.seriesLeaves() }
        is Condition.Crossing, is Condition.Delta, is Condition.Min, is Condition.Max -> listOf(this)
        else -> emptyList()
    }

/** Distinct (field, window) pairs a series condition needs. */
fun Condition.seriesWindows(): Set<Pair<String, Int>> =
    seriesLeaves()
        .map {
            when (it) {
                is Condition.Crossing -> it.field to it.window.observations
                is Condition.Delta -> it.field to it.window.observations
                is Condition.Min -> it.field to it.window.observations
                is Condition.Max -> it.field to it.window.observations
                else -> "" to 0
            }
        }.toSet()
