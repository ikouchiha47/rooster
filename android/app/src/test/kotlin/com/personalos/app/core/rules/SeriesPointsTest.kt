package com.personalos.app.core.rules

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesPointsTest {
    private fun points(vararg values: Double): List<SeriesPoint> = values.mapIndexed { index, value -> SeriesPoint(timestampMs = index.toLong(), value = value) }

    @Test
    fun `window beyond retention refuses`() {
        assertThrows(InsufficientSeriesException::class.java) {
            SeriesEvaluator.evaluatePoints(
                points = points(1.0, 2.0),
                condition = Condition.Crossing("temp_c", CrossingDirection.ABOVE, 1.5, Window(5)),
                retentionObservations = 3,
            )
        }
    }

    @Test
    fun `a gap inside the window stops crossing and delta`() {
        val gapped =
            listOf(
                SeriesPoint(timestampMs = 1L, value = 10.0, gapAfter = true),
                SeriesPoint(timestampMs = 2L, value = 12.0),
            )
        assertFalse(
            SeriesEvaluator.evaluatePoints(
                points = gapped,
                condition = Condition.Crossing("temp_c", CrossingDirection.ABOVE, 11.0, Window(2)),
            ),
        )
        assertFalse(
            SeriesEvaluator.evaluatePoints(
                points = gapped,
                condition = Condition.Delta("temp_c", 1.0, Window(2)),
            ),
        )
    }

    @Test
    fun `a stale window does not fire`() {
        assertFalse(
            SeriesEvaluator.evaluatePoints(
                points = points(10.0, 12.0),
                condition = Condition.Crossing("temp_c", CrossingDirection.ABOVE, 11.0, Window(2)),
                nowMs = 10_000L,
                maxStalenessMs = 1_000L,
            ),
        )
        assertTrue(
            SeriesEvaluator.evaluatePoints(
                points =
                    listOf(
                        SeriesPoint(timestampMs = 9_500L, value = 10.0),
                        SeriesPoint(timestampMs = 9_900L, value = 12.0),
                    ),
                condition = Condition.Crossing("temp_c", CrossingDirection.ABOVE, 11.0, Window(2)),
                nowMs = 10_000L,
                maxStalenessMs = 1_000L,
            ),
        )
    }

    @Test
    fun `dispatch partitions item from series without kind names`() {
        val partition =
            RuleDispatcher.partition(
                listOf(
                    RuleDispatcher.DispatchableRule("a", Condition.Subject("news")),
                    RuleDispatcher.DispatchableRule("b", Condition.Crossing("temp_c", CrossingDirection.ABOVE, 1.0, Window(2))),
                ),
            )
        assertTrue(partition.item.map { it.id } == listOf("a"))
        assertTrue(partition.series.map { it.id } == listOf("b"))
    }
}
