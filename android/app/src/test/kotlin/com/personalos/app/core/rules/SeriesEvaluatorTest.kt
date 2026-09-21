package com.personalos.app.core.rules

import com.personalos.app.core.catalog.MeasureKind
import com.personalos.app.core.catalog.MeasureUnsupportedException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesEvaluatorTest {
    @Test
    fun `crossing above fires on an edge`() {
        val cond = Condition.Crossing("temp_c", CrossingDirection.ABOVE, 11.0, Window(2))
        assertTrue(SeriesEvaluator.evaluate(listOf(10.0, 12.0), cond))
    }

    @Test
    fun `crossing above does not fire on a level`() {
        val cond = Condition.Crossing("temp_c", CrossingDirection.ABOVE, 11.0, Window(2))
        assertFalse(SeriesEvaluator.evaluate(listOf(12.0, 13.0), cond))
    }

    @Test
    fun `delta on a counter facet throws`() {
        val cond = Condition.Delta("amount", 5.0, Window(2))
        assertThrows(MeasureUnsupportedException::class.java) {
            SeriesEvaluator.evaluate(listOf(1.0, 7.0), cond, MeasureKind.COUNTER)
        }
    }

    @Test
    fun `min and max compare the window aggregate`() {
        assertTrue(
            SeriesEvaluator.evaluate(
                listOf(12.0, 9.0, 14.0),
                Condition.Min("temp_c", FieldOp.LT, 10.0, Window(3)),
            ),
        )
        assertFalse(
            SeriesEvaluator.evaluate(
                listOf(12.0, 11.0, 14.0),
                Condition.Min("temp_c", FieldOp.LT, 10.0, Window(3)),
            ),
        )
        assertTrue(
            SeriesEvaluator.evaluate(
                listOf(12.0, 19.0, 14.0),
                Condition.Max("temp_c", FieldOp.GTE, 19.0, Window(3)),
            ),
        )
    }
}
